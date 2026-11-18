package app.mjolnir.desktop

import app.mjolnir.client.FileSeenDeliveries
import app.mjolnir.client.InboxDrainer
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Messages
import app.mjolnir.client.Outbox
import app.mjolnir.client.OutboxResult
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.client.RelayClient
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.client.cleanStaging
import app.mjolnir.client.sendInBatches
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.extractUrls
import app.mjolnir.protocol.topFolder
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

private const val RETRY_DELAY_MS = 30_000L
const val TOOLTIP_IDLE = "Mjolnir"

/** The tray app's long-lived logic: relay connection, catching, outbox, and the actions the windows call. */
class AppController(private val dataDir: File = appDataDir()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val store = JsonFileStore(File(dataDir, "config.json"), DesktopConfig.serializer(), ::defaultDesktopConfig)
    private val _config = MutableStateFlow(store.read())
    val config: StateFlow<DesktopConfig> = _config.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _tooltip = MutableStateFlow(TOOLTIP_IDLE)
    val tooltip: StateFlow<String> = _tooltip.asStateFlow()

    private val _pairRequest = MutableStateFlow<RelayEvent.PairRequested?>(null)
    val pairRequest: StateFlow<RelayEvent.PairRequested?> = _pairRequest.asStateFlow()

    /** Name of the phone just paired, for the pairing window. */
    private val _pairedWith = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pairedWith: SharedFlow<String> = _pairedWith

    private val _notifications = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val notifications: SharedFlow<String> = _notifications

    private val clientLock = Any()
    private var client: RelayClient? = null
    private var clientUrl: String? = null
    private var serviceJob: Job? = null
    private val serviceSwitch = Mutex()

    private val seen = FileSeenDeliveries(File(dataDir, "seen.json"))
    private val outbox = Outbox(
        JsonFileStore(File(dataDir, "outbox.json"), ListSerializer(OutgoingFiles.serializer())) { emptyList() },
        api = { api() ?: throw RelayException(RelayErrorKind.NETWORK, "no relay configured") },
        open = ::openAt,
    )
    private val outboxRunning = Mutex()
    @Volatile private var outboxAgain = false
    @Volatile private var retryScheduled = false

    fun notify(message: String) {
        _notifications.tryEmit(message)
    }

    /** The relay client for the configured URL, or null while no relay is set. */
    fun api(): RelayClient? = synchronized(clientLock) {
        val url = _config.value.relayUrl.takeIf { it.isNotBlank() } ?: return null
        if (client == null || clientUrl != url) {
            client?.close()
            client = RelayClient.create(url) { store.read().token }
            clientUrl = url
        }
        client
    }

    private fun updateConfig(change: (DesktopConfig) -> DesktopConfig): DesktopConfig =
        store.update(change).also { _config.value = it }

    // ---- service ------------------------------------------------------------------------------------------------

    fun start() {
        scope.launch { restartService() }
    }

    private suspend fun restartService() = serviceSwitch.withLock {
        serviceJob?.cancelAndJoin()
        serviceJob = scope.launch { serviceLoop() }
    }

    private suspend fun serviceLoop() {
        while (true) {
            _config.first { it.relayUrl.isNotBlank() }
            val api = api() ?: continue
            var token: String? = null
            try {
                _config.value = ensureRegistered(store, api)
                token = _config.value.token
                refreshPeers()
                kickOutbox()
                CatchService(
                    api,
                    InboxDrainer(api, seen, catcher(api)::handle),
                    onPairRequest = { _pairRequest.value = it },
                    onPeersChanged = { refreshPeers() },
                    afterDrain = { cleanStagingFolder(api) },
                    onConnected = { kickOutbox() },
                ).run()
                delay(RETRY_DELAY_MS) // the events flow ended (client closed): start over
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                if (e.kind == RelayErrorKind.UNAUTHORIZED) {
                    if (!reRegisterIfStill(token)) delay(RETRY_DELAY_MS)
                } else {
                    delay(RETRY_DELAY_MS)
                }
            } catch (e: Exception) {
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private fun catcher(api: RelayClient) = DesktopCatcher(
        api, TabOpener(),
        saveDir = { _config.value.saveDirFile() },
        notify = { notify(it) },
    )

    private suspend fun cleanStagingFolder(api: RelayClient) {
        try {
            val keep = api.inbox().flatMap { d -> d.files.map { it.id } }.toSet()
            cleanStaging(File(_config.value.saveDirFile(), ".mjolnir-partial"), keep)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // next drain tries again
        }
    }

    private val reRegistering = Mutex()

    /**
     * Re-registers after the relay rejected [rejectedToken], unless another caller already replaced it.
     * Returns false only when re-registering failed (e.g. no connection).
     */
    private suspend fun reRegisterIfStill(rejectedToken: String?): Boolean = reRegistering.withLock {
        val api = api() ?: return@withLock false
        if (store.read().token != rejectedToken) return@withLock true
        try {
            _config.value = reRegister(store, api)
            _peers.value = emptyList()
            notify(Messages.RESET)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** UNAUTHORIZED seen outside the service loop: re-register (once), then restart the service. */
    private suspend fun recoverUnauthorized(rejectedToken: String?) {
        reRegisterIfStill(rejectedToken)
        restartService()
    }

    suspend fun refreshPeers() {
        val api = api() ?: return
        val token = store.read().token
        try {
            _peers.value = api.peers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            if (e.kind == RelayErrorKind.UNAUTHORIZED) scope.launch { recoverUnauthorized(token) }
        } catch (e: Exception) {
            // keep the old list
        }
    }

    // ---- outbox -------------------------------------------------------------------------------------------------

    /** Processes every queued item; concurrent kicks coalesce into one more pass. */
    fun kickOutbox() {
        outboxAgain = true
        scope.launch {
            if (!outboxRunning.tryLock()) return@launch
            try {
                while (outboxAgain) {
                    outboxAgain = false
                    for (item in outbox.pending()) {
                        try {
                            processItem(item)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            scheduleRetry() // e.g. a local I/O error reading the queue
                        }
                    }
                }
            } finally {
                outboxRunning.unlock()
            }
            if (outboxAgain) kickOutbox()
        }
    }

    private suspend fun processItem(item: OutgoingFiles) {
        if (api() == null) return
        val token = store.read().token
        val name = displayName(item.files)
        val result = try {
            outbox.process(item.id) { done, total ->
                val pct = if (total <= 0) 100 else (done * 100 / total).toInt()
                _tooltip.value = "Mjolnir: sending $name… $pct%"
            }
        } finally {
            _tooltip.value = TOOLTIP_IDLE
        }
        when (result) {
            is OutboxResult.Done -> notify(Messages.sent(0, item.files.size, item.toName))
            is OutboxResult.Retry -> scheduleRetry()
            is OutboxResult.Failed -> {
                notify(Messages.notSent(Messages.failureReason(result.kind, item.toName)))
                if (result.kind == RelayErrorKind.UNAUTHORIZED) recoverUnauthorized(token)
            }
        }
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        scope.launch {
            delay(RETRY_DELAY_MS)
            retryScheduled = false
            kickOutbox()
        }
    }

    // ---- actions ------------------------------------------------------------------------------------------------

    /** Sends links now and queues files; returns the message to show. */
    suspend fun send(target: Peer, tabs: List<Tab>, files: List<LocalUpload>): SendResult {
        val api = api() ?: return SendResult(Messages.notSent(Messages.failureReason(RelayErrorKind.NETWORK, target.name)), emptyList(), false)
        val token = store.read().token
        val parts = mutableListOf<String>()
        var sentTabs = emptyList<Tab>()
        var error: RelayException? = null
        if (tabs.isNotEmpty()) {
            val outcome = sendInBatches(api, target.deviceId, tabs)
            sentTabs = outcome.sentTabs
            error = outcome.error
            if (sentTabs.isNotEmpty()) parts += Messages.sent(sentTabs.size, 0, target.name)
        }
        if (files.isNotEmpty()) {
            val now = System.currentTimeMillis()
            for (group in groupForSending(files)) {
                outbox.enqueue(OutgoingFiles(UUID.randomUUID().toString(), target.deviceId, target.name, group, createdAtEpochMs = now))
            }
            parts += Messages.queued(files.size, target.name)
            kickOutbox()
        }
        if (error != null) {
            parts += Messages.notSent(Messages.failureReason(error.kind, target.name))
            if (error.kind == RelayErrorKind.UNAUTHORIZED) scope.launch { recoverUnauthorized(token) }
        }
        if (sentTabs.isNotEmpty() || files.isNotEmpty()) updateConfig { it.copy(lastPhoneId = target.deviceId) }
        return SendResult(parts.joinToString(" "), sentTabs, files.isNotEmpty())
    }

    /** Tray action: sends the links found in the clipboard to the last phone. */
    fun sendClipboard() {
        scope.launch {
            val phoneId = _config.value.lastPhoneId ?: return@launch
            val phone = _peers.value.find { it.deviceId == phoneId } ?: Peer(phoneId, lastPhoneName(), app.mjolnir.protocol.DeviceKind.PHONE)
            val text = withContext(Dispatchers.Main) { clipboardText() }
            val urls = extractUrls(text ?: "")
            if (urls.isEmpty()) return@launch notify(Messages.NO_LINKS)
            notify(send(phone, urls.map { Tab(it) }, emptyList()).message)
        }
    }

    fun lastPhoneName(): String =
        _peers.value.find { it.deviceId == _config.value.lastPhoneId }?.name ?: "phone"

    fun openReceivedFiles() {
        val dir = _config.value.saveDirFile()
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(dir)
            else ProcessBuilder("explorer.exe", dir.absolutePath).start()
        } catch (e: Exception) {
            // nothing sensible to do
        }
    }

    suspend fun accept(request: RelayEvent.PairRequested) = answer(request, accept = true)
    suspend fun reject(request: RelayEvent.PairRequested) = answer(request, accept = false)

    private suspend fun answer(request: RelayEvent.PairRequested, accept: Boolean) {
        _pairRequest.compareAndSet(request, null)
        val api = api() ?: return
        try {
            if (accept) {
                api.acceptPairing(request.pairingId)
                refreshPeers()
                val name = request.from.name
                if (_pairedWith.subscriptionCount.value > 0) _pairedWith.emit(name) else notify("Paired with $name")
            } else {
                api.rejectPairing(request.pairingId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            notify(Messages.failureReason(e.kind, request.from.name))
        }
    }

    suspend fun unpair(peer: Peer) {
        val api = api() ?: return
        try {
            api.unpair(peer.deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            notify(Messages.failureReason(e.kind, peer.name))
        }
        refreshPeers()
    }

    /** Switches relay: forgets credentials and pairings, then registers on the new one. */
    suspend fun changeRelay(url: String) {
        if (url == _config.value.relayUrl) return
        serviceSwitch.withLock { serviceJob?.cancelAndJoin(); serviceJob = null }
        updateConfig { it.copy(relayUrl = url, deviceId = null, token = null, lastPhoneId = null) }
        _peers.value = emptyList()
        restartService()
    }

    /** Renames this device on the relay (when registered) and in the config; returns an error message or null. */
    suspend fun rename(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > app.mjolnir.protocol.Limits.MAX_DEVICE_NAME_LENGTH) return null
        val api = api()
        if (api != null && _config.value.token != null) {
            try {
                api.rename(trimmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                return Messages.failureReason(e.kind, trimmed)
            }
        }
        updateConfig { it.copy(deviceName = trimmed) }
        return null
    }

    fun setSaveDir(dir: File) {
        updateConfig { it.copy(saveDir = dir.absolutePath) }
    }

    fun setLaunchAtLogin(enabled: Boolean): Boolean {
        val ok = app.mjolnir.desktop.setLaunchAtLogin(enabled)
        if (ok) updateConfig { it.copy(launchAtLogin = enabled) }
        return ok
    }

    fun shutdown() {
        synchronized(clientLock) { client?.close(); client = null }
        scope.coroutineContext[Job]?.cancel()
    }
}

data class SendResult(val message: String, val sentTabs: List<Tab>, val filesQueued: Boolean)

/** Name shown while uploading: the single file, the shared top folder, or "n files". */
fun displayName(files: List<LocalUpload>): String {
    if (files.size == 1) return files.single().relativePath.substringAfterLast('/')
    val tops = files.map { topFolder(it.relativePath) }.toSet()
    return tops.singleOrNull() ?: Messages.items(0, files.size)
}

/** Opens a queued file positioned at [offset]. */
fun openAt(file: LocalUpload, offset: Long): InputStream =
    FileInputStream(File(file.path)).also { it.channel.position(offset) }

private fun clipboardText(): String? = try {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
} catch (e: Exception) {
    null
}
