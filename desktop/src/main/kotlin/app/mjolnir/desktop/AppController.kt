package app.mjolnir.desktop

import app.mjolnir.client.FileSeenDeliveries
import app.mjolnir.client.InboxDrainer
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Messages
import app.mjolnir.client.Outbox
import app.mjolnir.client.Nicknames
import app.mjolnir.client.OutboxResult
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayClient
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.client.RelayUrls
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import app.mjolnir.client.ClaimOutcome
import app.mjolnir.client.claimAndAwait
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Tabs of the main window; `GUEST` is unused until the guest-room plan. */
enum class MainTab { SEND, GUEST, RECEIVED, DEVICES, SETTINGS }

private const val RETRY_DELAY_MS = 30_000L
const val TOOLTIP_IDLE = "Mjolnir"

/** The tray app's long-lived logic: relay connection, catching, outbox, and the actions the windows call. */
class AppController(
    private val dataDir: File = appDataDir(),
    /** Runs everything that must outlive a window: relay calls, sends, lifecycle changes. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val apiFactory: (url: String, token: () -> String?) -> RelayApi = { url, token -> RelayClient.create(url, token) },
) {

    private val store = JsonFileStore(File(dataDir, "config.json"), DesktopConfig.serializer(), ::defaultDesktopConfig)
    val nicknames = Nicknames(JsonFileStore(File(dataDir, "nicknames.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() })
    val history = ReceivedHistory(JsonFileStore(File(dataDir, "received.json"), ListSerializer(ReceivedEntry.serializer())) { emptyList() })
    private val _config = MutableStateFlow(store.read())
    val config: StateFlow<DesktopConfig> = _config.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _send = MutableStateFlow(SendState())
    /** The send queue lives here, not in a window, so closing the window keeps it. */
    val send: StateFlow<SendState> = _send.asStateFlow()

    fun updateSend(change: (SendState) -> SendState) {
        _send.update(change)
    }

    private val _openRequests = MutableSharedFlow<MainTab>(extraBufferCapacity = 4)
    /** Asks the UI to show the main window on a tab. */
    val openRequests: SharedFlow<MainTab> = _openRequests

    fun openWindow(tab: MainTab = MainTab.SEND) {
        _openRequests.tryEmit(tab)
    }

    private fun syncTargets() = _send.update { it.withTargets(_peers.value, _config.value.lastPhoneId) }

    private val _tooltip = MutableStateFlow(TOOLTIP_IDLE)
    val tooltip: StateFlow<String> = _tooltip.asStateFlow()

    private val _pairRequest = MutableStateFlow<RelayEvent.PairRequested?>(null)
    val pairRequest: StateFlow<RelayEvent.PairRequested?> = _pairRequest.asStateFlow()

    /** Name of the device just paired, for the pairing window. */
    private val _pairedWith = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pairedWith: SharedFlow<String> = _pairedWith

    /** Emits when this device's shown code was used up by a request it rejected, so the pair window shows a fresh one. */
    private val _codeUsedUp = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val codeUsedUp: SharedFlow<Unit> = _codeUsedUp

    private val _notifications = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val notifications: SharedFlow<String> = _notifications

    private val clientLock = Any()
    private var client: RelayApi? = null
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

    /**
     * The relay client for the configured URL, or null while no valid relay is set. A hand-edited config with plain
     * http to a remote host (or any URL [RelayUrls.normalize] rejects) gets no client; Settings shows the error.
     */
    fun api(): RelayApi? = synchronized(clientLock) {
        val url = RelayUrls.normalize(_config.value.relayUrl) ?: return null
        if (client == null || clientUrl != url) {
            (client as? AutoCloseable)?.close()
            client = apiFactory(url) { store.read().token }
            clientUrl = url
        }
        client
    }

    private fun updateConfig(change: (DesktopConfig) -> DesktopConfig): DesktopConfig =
        store.update(change).also { _config.value = it; syncTargets() }

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
            _config.first { RelayUrls.normalize(it.relayUrl) != null }
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
                    onPairRequest = { onPairRequested(it) },
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

    private fun catcher(api: RelayApi) = DesktopCatcher(
        api, TabOpener(),
        saveDir = { _config.value.saveDirFile() },
        notify = { notify(it) },
        history = history,
        openLinks = { _config.value.openLinksOnArrival },
        displayName = nicknames::displayName,
    )

    private suspend fun cleanStagingFolder(api: RelayApi) {
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
            setPeers(emptyList())
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

    private fun setPeers(peers: List<Peer>) {
        _peers.value = peers
        syncTargets()
    }

    suspend fun refreshPeers() {
        val api = api() ?: return
        val token = store.read().token
        try {
            setPeers(api.peers())
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
                notify(Messages.notSent(result.message ?: Messages.failureReason(result.kind, item.toName)))
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

    /**
     * Sends in [scope], so closing the window cannot cancel it. [deliver] gets the result and returns false when its
     * window is gone; the result is then shown as a tray notification instead.
     */
    fun startSend(target: Peer, tabs: List<Tab>, files: List<LocalUpload>, deliver: (SendResult) -> Boolean): Job =
        scope.launch {
            val result = try {
                send(target, tabs, files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // a local failure, e.g. writing outbox.json
                SendResult(Messages.notSent(Messages.failureReason(RelayErrorKind.SERVER, target.name)), emptyList(), false)
            }
            if (!deliver(result)) notify(result.message)
        }

    /**
     * Sends the checked links and all files of the queue to the chosen target, in [scope]. Afterwards the sent links
     * and files leave the queue; unchecked links stay.
     */
    fun sendSelected(): Job {
        val s = _send.value
        val target = s.target
        val tabs = s.tabs()
        val files = s.selectedFiles
        return scope.launch {
            if (target == null || !s.canSend) return@launch
            val r = try {
                send(target, tabs, files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // a local failure, e.g. writing outbox.json
                SendResult(Messages.notSent(Messages.failureReason(RelayErrorKind.SERVER, target.name)), emptyList(), false)
            }
            val sentUrls = r.sentTabs.map { it.url }.toSet()
            updateSend { cur ->
                val kept = if (r.filesQueued) cur.copy(files = cur.files - files.toSet(), selected = cur.selected - files.mapTo(HashSet()) { uploadUnitKey(it) }) else cur
                kept.withoutLinks(sentUrls).copy(notice = r.message)
            }
            notify(r.message)
        }
    }

    /** Explorer's *Send to* / a second launch: queues [paths] (scanned off the UI thread), aims at the last target, shows the Send tab. */
    fun handOff(paths: List<File>): Job = scope.launch {
        val scan = withContext(Dispatchers.IO) { scanFiles(paths) }
        updateSend { s ->
            val last = _config.value.lastPhoneId
            (if (last != null) s.selectTarget(last) else s).withScanned(scan)
        }
        openWindow(MainTab.SEND)
    }

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

    /** Tray action: sends the links found in the clipboard to the last device. */
    fun sendClipboard() {
        scope.launch {
            val targetId = _config.value.lastPhoneId ?: return@launch // the last target, PC or phone
            val target = _peers.value.find { it.deviceId == targetId } ?: Peer(targetId, lastPhoneName(), app.mjolnir.protocol.DeviceKind.PC)
            val text = withContext(Dispatchers.Main) { clipboardText() }
            val urls = extractUrls(text ?: "")
            if (urls.isEmpty()) return@launch notify(Messages.NO_LINKS)
            notify(send(target, urls.map { Tab(it) }, emptyList()).message)
        }
    }

    /**
     * Claims a code typed on this device and waits for the other device to accept. Runs in [scope], so closing the
     * window does not stop it; the caller only waits for the outcome. Refreshes the device list on success.
     */
    suspend fun pairWithCode(
        input: String,
        /** Shows the outcome in the pair window; returns false if that window is gone (then it becomes a tray notification). */
        deliver: suspend (ClaimOutcome) -> Boolean = { false },
        onWaiting: (Peer) -> Unit,
    ): ClaimOutcome {
        val api = api()
        val token = store.read().token
        return scope.async {
            val outcome = if (api == null) ClaimOutcome.Failed("Can't reach the relay. Check your connection.")
            else claimAndAwait(api, input, onWaiting)
            when (outcome) {
                is ClaimOutcome.Paired -> {
                    if (_config.value.lastPhoneId == null) updateConfig { it.copy(lastPhoneId = outcome.peer.deviceId) }
                    refreshPeers()
                    _pairedWith.tryEmit(outcome.peer.name)
                }
                is ClaimOutcome.Failed -> if (outcome.kind == RelayErrorKind.UNAUTHORIZED) recoverUnauthorized(token)
            }
            if (!deliver(outcome)) {
                notify(when (outcome) { is ClaimOutcome.Paired -> "Paired with ${outcome.peer.name}"; is ClaimOutcome.Failed -> outcome.message })
            }
            outcome
        }.await()
    }

    fun lastPhoneName(): String =
        _peers.value.find { it.deviceId == _config.value.lastPhoneId }?.name ?: "device"

    fun openReceivedFiles() {
        scope.launch {
            try {
                val dir = _config.value.saveDirFile()
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(dir)
                else ProcessBuilder("explorer.exe", dir.absolutePath).start()
            } catch (e: Exception) {
                // nothing sensible to do
            }
        }
    }

    internal fun onPairRequested(request: RelayEvent.PairRequested) {
        _pairRequest.value = request
    }

    private val answering = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * Answers a pairing request in [scope] (never in a window's scope: the dialog disappears when the request clears).
     * The request is cleared only after the relay call returns. Repeated clicks while answering are ignored.
     */
    fun answerPairRequest(request: RelayEvent.PairRequested, accept: Boolean): Job? {
        if (!answering.add(request.pairingId)) return null
        return scope.launch {
            try {
                answer(request, accept)
            } finally {
                _pairRequest.compareAndSet(request, null)
                answering.remove(request.pairingId)
            }
        }
    }

    private suspend fun answer(request: RelayEvent.PairRequested, accept: Boolean) {
        val api = api() ?: return
        try {
            if (accept) {
                api.acceptPairing(request.pairingId)
            } else {
                // The claim used the code up whether or not the reject reaches the relay: show a fresh one.
                try {
                    api.rejectPairing(request.pairingId)
                } finally {
                    _codeUsedUp.tryEmit(Unit)
                }
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            // The dialog is gone, so a failed accept can't be retried; the claim used the code up.
            if (accept && e.kind != RelayErrorKind.UNAUTHORIZED) _codeUsedUp.tryEmit(Unit)
            notify(couldntAnswer(Messages.failureReason(e.kind, request.from.name)))
            return
        }
        if (_config.value.lastPhoneId == null) updateConfig { it.copy(lastPhoneId = request.from.deviceId) }
        refreshPeers()
        val name = request.from.name
        if (_pairedWith.subscriptionCount.value > 0) _pairedWith.emit(name) else notify("Paired with $name")
    }

    fun startUnpair(peer: Peer): Job = scope.launch { unpair(peer) }

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

    /** Switches relay in [scope]: forgets credentials and pairings, then registers on the new one. */
    fun changeRelay(url: String): Job = scope.launch {
        relaySwitch.withLock {
            if (url == _config.value.relayUrl) return@withLock
            serviceSwitch.withLock { serviceJob?.cancelAndJoin(); serviceJob = null }
            updateConfig { it.copy(relayUrl = url, deviceId = null, token = null, lastPhoneId = null) }
            setPeers(emptyList())
            restartService()
        }
    }

    private val relaySwitch = Mutex()

    /** Renames in [scope]; [onResult] gets an error message or null. */
    fun startRename(name: String, onResult: (String?) -> Unit): Job = scope.launch { onResult(rename(name)) }

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
        scope.launch { updateConfig { it.copy(saveDir = dir.absolutePath) } }
    }

    /** Runs `reg` in [scope]; the setting is stored only when it succeeded. */
    fun setLaunchAtLogin(enabled: Boolean): Job = scope.launch {
        if (app.mjolnir.desktop.setLaunchAtLogin(enabled)) updateConfig { it.copy(launchAtLogin = enabled) }
    }

    /** Runs PowerShell (or deletes the shortcut) in [scope]; the setting is stored only when it succeeded. */
    fun setSendToMenu(enabled: Boolean): Job = scope.launch {
        if (setSendToShortcut(enabled)) updateConfig { it.copy(sendToMenu = enabled) }
    }

    /** Recreates the *Send to* shortcut at startup when the setting is on (repairs a deleted or moved one). */
    fun ensureSendToShortcut(): Job? =
        if (_config.value.sendToMenu) scope.launch { setSendToShortcut(true) } else null

    fun shutdown() {
        synchronized(clientLock) { (client as? AutoCloseable)?.close(); client = null }
        scope.coroutineContext[Job]?.cancel()
    }
}

fun couldntGetCode(reason: String) = "Couldn't get a code: $reason."
fun couldntAnswer(reason: String) = "Couldn't answer the request: $reason."

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
