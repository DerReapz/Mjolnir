package app.mjolnir.desktop

import app.mjolnir.client.ChatController
import app.mjolnir.client.ChatMediaCache
import app.mjolnir.client.ChatOutbox
import app.mjolnir.client.uniqueTarget
import app.mjolnir.desktop.media.DesktopVideoThumbnailer
import app.mjolnir.desktop.media.deletePlayerCopies
import app.mjolnir.desktop.media.playerCopiesDir
import app.mjolnir.desktop.ui.ChatMediaSource
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.safeFileName
import app.mjolnir.protocol.safeRelativePath
import app.mjolnir.client.ChatStore
import app.mjolnir.client.FileSeenDeliveries
import app.mjolnir.client.IncomingMessage
import app.mjolnir.client.Exchanged
import app.mjolnir.client.GuestRoomController
import app.mjolnir.client.GuestRoomUi
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
import app.mjolnir.client.Platform
import app.mjolnir.client.UpdateState
import app.mjolnir.client.Updater
import app.mjolnir.client.newMjolnirHttpClient
import app.mjolnir.client.cleanStaging
import app.mjolnir.client.guestRoomSecretStore
import app.mjolnir.client.sendInBatches
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.SpaceRef
import app.mjolnir.client.DestinationKey
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
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import app.mjolnir.client.ClaimOutcome
import app.mjolnir.client.claimAndAwait
import app.mjolnir.client.spaceErrorCopy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Tabs of the main window. */
enum class MainTab { SEND, CHATS, GUEST, RECEIVED, DEVICES, SETTINGS }

/** The sidebar's relay line. */
enum class RelayStatus(val label: String) {
    /** No valid relay address. */
    NOT_SET("Relay not set"),
    /** An address is set, but the events stream is not connected right now (still connecting, or reconnecting). */
    CONNECTING("Connecting…"),
    /** The events stream is connected. */
    CONNECTED("Relay connected"),
}

private const val RETRY_DELAY_MS = 30_000L
const val TOOLTIP_IDLE = "Mjolnir"

/** The tray app's long-lived logic: relay connection, catching, outbox, and the actions the windows call. */
class AppController(
    private val dataDir: File = appDataDir(),
    /** Runs everything that must outlive a window: relay calls, sends, lifecycle changes. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Registry write for *Start at sign-in*; returns whether it worked. Injectable so tests need no registry. */
    private val launchAtLoginSetter: (Boolean) -> Boolean = { app.mjolnir.desktop.setLaunchAtLogin(it) },
    /** Creates or deletes the Explorer *Send to* shortcut; returns whether it worked. */
    private val sendToSetter: (Boolean) -> Boolean = { setSendToShortcut(it) },
    /** Tags a downloaded chat file as from the internet (the Mark of the Web), as caught files are. */
    private val markDownloaded: (File) -> Unit = ::markOfTheWeb,
    /** The temp folder of copies opened in the system video player: deleted at start and when the chats are cleared. */
    private val playerCopies: File = playerCopiesDir(),
    /**
     * Builds the app updater. Its HTTP client is its own, never a relay client's, so the relay's bearer token and app
     * headers are not sent to GitHub. Downloads go to `<app data>/updates`.
     */
    newUpdater: (CoroutineScope) -> Updater = { s -> Updater(newMjolnirHttpClient(), Platform.WINDOWS_MSI, appVersion(), File(dataDir, "updates"), s) },
    /** Starts the downloaded MSI ([MsiInstaller]); tests inject a fake. */
    private val installer: InstallerLauncher = MsiInstaller(),
    /** Ends the app once the installer runs; null means [shutdown] and exit the process (the shutdown hook frees the instance lock). */
    exitApp: (() -> Unit)? = null,
    private val apiFactory: (url: String, token: () -> String?) -> RelayApi = { url, token -> RelayClient.create(url, appVersion(), token) },
) {
    private val exitApp: () -> Unit = exitApp ?: { shutdown(); exitProcess(0) }

    private val updater: Updater = newUpdater(scope)
    /** The app update: available, downloading, failed, or ready (handed to the installer). */
    val updateState: StateFlow<UpdateState> = updater.state
    /** The relay is older than this app: the banner says it needs a newer relay. */
    val relayTooOld: StateFlow<Boolean> = updater.relayTooOld
    private var relayVersionJob: Job? = null

    private val store = JsonFileStore(File(dataDir, "config.json"), DesktopConfig.serializer(), ::defaultDesktopConfig)
    val nicknames = Nicknames(JsonFileStore(File(dataDir, "nicknames.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() })
    val history = ReceivedHistory(JsonFileStore(File(dataDir, "received.json"), ListSerializer(ReceivedEntry.serializer())) { emptyList() })
    private val _config = MutableStateFlow(store.read())
    val config: StateFlow<DesktopConfig> = _config.asStateFlow()

    private val connected = MutableStateFlow(false)
    private fun statusOf(config: DesktopConfig, up: Boolean) = when {
        RelayUrls.normalize(config.relayUrl) == null -> RelayStatus.NOT_SET
        !up -> RelayStatus.CONNECTING
        else -> RelayStatus.CONNECTED
    }
    /** Live connection state: [RelayEvent.Connected] received and not since dropped. */
    val relayStatus: StateFlow<RelayStatus> = combine(_config, connected, ::statusOf)
        .stateIn(scope, SharingStarted.Eagerly, statusOf(_config.value, false))

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    /** Spaces this device is in: the list, invite code, join flow and admin actions. */
    val spaces = SpacesController(
        api = ::api, scope = scope,
        onPeersAffected = { scope.launch { refreshAfterSpaceChange() } },
        onUnauthorized = { scope.launch { recoverUnauthorized(store.read().token) } },
    )

    /** Chat photos, GIFs and files fetched from the relay: an LRU cache in the app data folder `chat-media/`. */
    private val chatMedia = ChatMediaCache(File(dataDir, "chat-media"))

    /**
     * The chats of this device's spaces; their cache lives in the app data folder `chats/`, sends with files queue in
     * `chat-outbox/`.
     */
    val chats = ChatController(
        api = ::api, store = ChatStore(File(dataDir, "chats").also { it.mkdirs() }), scope = scope,
        outbox = ChatOutbox(File(dataDir, "chat-outbox")), media = chatMedia,
        me = { _config.value.deviceId },
    )

    /** What the chat bubbles show: attachments through [chatMedia], fetched with the current relay connection. */
    val chatMediaSource: ChatMediaSource = object : ChatMediaSource {
        private fun relay() = api() ?: throw RelayException(RelayErrorKind.NETWORK, "no relay")
        override suspend fun thumbnail(att: Attachment): File? = chatMedia.thumbnail(att, relay())
        override suspend fun file(att: Attachment): File = chatMedia.get(att, relay())
        override suspend fun video(att: Attachment, onProgress: (Long, Long) -> Unit): File = chatMedia.get(att, relay(), onProgress)
        override fun pin(fileId: String) = chatMedia.pin(fileId)
        override fun unpin(fileId: String) = chatMedia.unpin(fileId)
        override val connects: StateFlow<Int> get() = chats.connects
    }

    private val _selectedChat = MutableStateFlow<String?>(null)
    /** The chat the Chats tab shows; it outlives the tab, so *New chat…* elsewhere can open the chat it made. */
    val selectedChat: StateFlow<String?> = _selectedChat.asStateFlow()
    fun selectChat(chatId: String?) { _selectedChat.value = chatId }

    /** Set by the Chats tab while a dialog or the image viewer covers the conversation: Ctrl+V then sends nothing. */
    @Volatile var chatOverlayOpen = false

    /** Where Ctrl+V on the Chats tab sends files: the chat on screen if this device is in it and nothing covers it, else null. */
    fun chatPasteTarget(): String? =
        if (chatOverlayOpen) null else chats.chats.value.firstOrNull { it.id == _selectedChat.value && it.member }?.id

    private val _chatNotice = MutableStateFlow<String?>(null)
    /** The last failed chat action, as a sentence; null once dismissed. */
    val chatNotice: StateFlow<String?> = _chatNotice.asStateFlow()
    fun dismissChatNotice() { _chatNotice.value = null; _chatInfo.value = null }

    private val _chatInfo = MutableStateFlow<String?>(null)
    /** The last chat download's result ("Saved to …"); null once dismissed. Failures go to [chatNotice]. */
    val chatInfo: StateFlow<String?> = _chatInfo.asStateFlow()

    /** Sends [files] (folders as their files) to [chatId]; they upload in the background and show as pending sends. */
    fun sendChatFiles(chatId: String, files: List<File>) {
        if (files.isEmpty()) return
        scope.launch { chatCall { send(chatId, null, files, DesktopVideoThumbnailer) } }
    }

    /** Saves [attachments] of [m] into the save folder (see [saveChatFiles]); the result shows as [chatInfo] or [chatNotice]. */
    fun downloadChatFiles(m: ChatMessage, attachments: List<Attachment>) {
        if (attachments.isEmpty()) return
        scope.launch { saveChatFiles(m, attachments) }
    }

    /**
     * Saves [attachments] of [m] into the save folder, with the catch rules: safe names, a free name next to existing
     * files ("name (1).ext"), a sent folder under one free top-folder name, and the Mark of the Web. Then shows
     * "Saved to {folder}", or the failure ("Message deleted") as the notice.
     */
    internal suspend fun saveChatFiles(m: ChatMessage, attachments: List<Attachment>) {
        _chatInfo.value = null
        _chatNotice.value = null // an earlier failure would hide "Saved to"
        try {
            val root = withContext(Dispatchers.IO) { _config.value.saveDirFile() }
            val folders = HashMap<String, File>()
            for (att in attachments) {
                val path = att.relativePath?.let(::safeRelativePath)?.split('/')
                val dir = if (path == null || path.size < 2) root else {
                    val top = folders.getOrPut(path.first()) { withContext(Dispatchers.IO) { File(root, freeFolderName(root, path.first())).apply { mkdirs() } } }
                    File(top, path.subList(1, path.size - 1).joinToString("/")).also { withContext(Dispatchers.IO) { it.mkdirs() } }
                }
                val target = withContext(Dispatchers.IO) { uniqueTarget(dir, path?.last() ?: safeFileName(att.name)) }
                chats.download(m.chatId, att, target)
                withContext(Dispatchers.IO) { markDownloaded(target) }
            }
            _chatInfo.value = Messages.savedTo(root.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            _chatNotice.value = e.userMessage ?: Messages.chatError(e)
        } catch (e: Exception) {
            _chatNotice.value = "Couldn't save ${attachments.first().name}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** [name], or "name (1)", "name (2)", … if a file or folder of that name is in [root] already. */
    private fun freeFolderName(root: File, name: String): String =
        generateSequence(1) { it + 1 }.map { "$name ($it)" }.let { sequenceOf(name) + it }.first { !File(root, it).exists() }

    /**
     * Runs a chat action in the controller's scope, so closing the window cannot cancel it half way; a refusal becomes
     * [chatNotice] and the result is null.
     */
    suspend fun <T> chatCall(block: suspend ChatController.() -> T): T? = scope.async {
        try {
            chats.block()
        } catch (e: RelayException) {
            _chatNotice.value = Messages.chatError(e)
            null
        }
    }.await()

    /** Creates a chat in [space] and shows it in the Chats tab. */
    fun createChat(space: Space, name: String) {
        scope.launch { chatCall { createChat(space.id, name) }?.let { selectChat(it.id); openWindow(MainTab.CHATS) } }
    }

    /** Drops every chat, its cache and the screen state: after a re-registration or relay switch none of it is this device's any more. */
    private fun clearChats() {
        chats.clear()
        deletePlayerCopies(playerCopies)
        _selectedChat.value = null
        _chatNotice.value = null
        _chatInfo.value = null
    }

    init {
        deletePlayerCopies(playerCopies) // a run that ended without deleting them (deleteOnExit misses a crash)
        // Always on, and started before the first refresh: with no collector a new message would be dropped, not notified.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { chats.newMessages.collect(::onIncomingChatMessage) }
        // Ruling CM-R7: a file the relay refused as too large is named; the rest of its send went on.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { chats.sendNotices.collect { _chatNotice.value = it } }
    }

    /** A message in a chat that isn't open: a tray notification, unless that chat is open in the visible Chats tab. */
    internal fun onIncomingChatMessage(m: IncomingMessage) {
        if (chats.openChat.value == m.chatId && mainWindowVisible && mainWindowTab == MainTab.CHATS) return
        val name = nicknames.get(m.authorId) ?: m.authorName
        notify(if (m.mentionedMe) Messages.mentionNotification(name, m.chatName, m.text) else Messages.chatNotification(name, m.chatName, m.text))
    }

    /** The guest room this device owns: its link, countdown, guest and the list of what was exchanged. */
    val guestRoom = GuestRoomController(
        api = ::api, relayUrl = { RelayUrls.normalize(_config.value.relayUrl) ?: "" },
        secrets = guestRoomSecretStore(File(dataDir, "guest-room.json")),
        scope = scope, clock = System::currentTimeMillis,
    )

    private val _send = MutableStateFlow(SendState())
    /** The send queue lives here, not in a window, so closing the window keeps it. */
    val send: StateFlow<SendState> = _send.asStateFlow()

    fun updateSend(change: (SendState) -> SendState) {
        _send.update(change)
    }

    /** Set by the main window while it is shown; then results appear in the window instead of as tray notifications. */
    var mainWindowVisible: Boolean
        get() = _showing.value
        set(v) = synchronized(windowLock) { shown = v; if (!v) minimized = false; _showing.value = shown && !minimized }

    private val windowLock = Any()
    private var shown = false
    private var minimized = false
    private val _showing = MutableStateFlow(false)
    /** True while the main window is shown and not minimised: the one rule for "the user can see it" (notifications, read marks). */
    val windowShowing: StateFlow<Boolean> = _showing.asStateFlow()

    init {
        // Ruling CM-R2: the window coming back is a moment to retry sends held back by a network loss (Connected also does).
        scope.launch { windowShowing.collect { if (it) chats.resumeSends() } }
    }

    /** The window was minimised or restored; a minimised window counts as not visible. */
    fun setWindowMinimized(min: Boolean) = synchronized(windowLock) { minimized = min; _showing.value = shown && !minimized }

    /** The tab the main window is showing; a send result appears in the window only while this is [MainTab.SEND]. */
    @Volatile var mainWindowTab: MainTab = MainTab.SEND

    private val _openRequests = MutableSharedFlow<MainTab>(extraBufferCapacity = 4)
    /** Asks the UI to show the main window on a tab. */
    val openRequests: SharedFlow<MainTab> = _openRequests

    fun openWindow(tab: MainTab = MainTab.SEND) {
        _openRequests.tryEmit(tab)
    }

    private fun syncTargets() = _send.update { it.withTargets(_peers.value, _config.value.lastPhoneId, _config.value.lastSpaceId) }

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
            // The relay's version header (any response) tells the updater whether this relay is older than the app.
            relayVersionJob?.cancel()
            relayVersionJob = (client as? RelayClient)?.let { c ->
                scope.launch { c.relayVersion.filterNotNull().collect(updater::onRelayVersion) }
            }
        }
        client
    }

    private fun updateConfig(change: (DesktopConfig) -> DesktopConfig): DesktopConfig =
        store.update(change).also { _config.value = it; syncTargets() }

    // ---- service ------------------------------------------------------------------------------------------------

    fun start() {
        scope.launch { restartService() }
        scope.launch {
            updater.cleanOld() // installers left by an earlier run (the update it installed, or one never installed)
            updater.start()
        }
    }

    private val installing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Install (banner or tray): downloads the update (from `Available`, or `Failed` to try again); once it is `Ready`,
     * starts the installer and exits the app. An installer that won't start leaves `Failed` with the reason. A second
     * tap while this runs does nothing.
     */
    fun installUpdate(): Job = scope.launch {
        if (!installing.compareAndSet(false, true)) return@launch
        try {
            val s = updater.state.value
            if (s is UpdateState.Available || s is UpdateState.Failed) updater.download()
            val ready = updater.state.value as? UpdateState.Ready ?: return@launch
            try {
                withContext(Dispatchers.IO) { installer.launch(ready.file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updater.installFailed(INSTALLER_DIDNT_START)
                return@launch
            }
            exitApp()
        } finally {
            installing.set(false)
        }
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
                spaces.refresh()
                kickOutbox()
                CatchService(
                    api,
                    InboxDrainer(api, seen, catcher(api)::handle),
                    onPairRequest = { onPairRequested(it) },
                    // Also after unpair, space change and space deletion events: the refreshed list decides which nicknames still have a link.
                    onPeersChanged = { refreshAfterSpaceChange() },
                    afterDrain = { cleanStagingFolder(api) },
                    onConnected = { kickOutbox(); guestRoom.restore() },
                    onConnectionChange = { connected.value = it },
                    onSpaceChanged = { spaces.refresh() },
                    onChatEvent = chats::onEvent,
                    onSpaceDeleted = { onSpaceDeleted(it.space) },
                    onGuestRoomEvent = ::onGuestRoomEvent,
                    onUpdateAvailable = updater::onUpdateAvailable,
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
        recordGuest = guestRoom::record,
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
            nicknames.clear() // the new registration has no pairings
            spaces.clear()
            clearChats()
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

    /** Reloads the peer list; true only when the relay answered (a failure keeps the old list). */
    suspend fun refreshPeers(): Boolean {
        val api = api() ?: return false
        val token = store.read().token
        try {
            setPeers(api.peers())
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            if (e.kind == RelayErrorKind.UNAUTHORIZED) scope.launch { recoverUnauthorized(token) }
        } catch (e: Exception) {
            // keep the old list
        }
        return false
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
        val to = nicknames.get(item.to) ?: item.toName // the outbox keeps the real name; a nickname may have changed since
        val toGuest = isGuest(item.to)
        val result = try {
            outbox.process(item.id) { done, total ->
                val pct = if (total <= 0) 100 else (done * 100 / total).toInt()
                _tooltip.value = "Mjolnir: sending $name… $pct%"
                if (toGuest) recordSentToGuest(item, progress = done to total)
            }
        } finally {
            _tooltip.value = TOOLTIP_IDLE
        }
        when (result) {
            is OutboxResult.Done -> {
                if (toGuest) recordSentToGuest(result.item, progress = null)
                notify(Messages.sent(0, item.files.size, to, item.spaceName))
            }
            is OutboxResult.Retry -> scheduleRetry()
            is OutboxResult.Failed -> {
                if (toGuest) recordSentToGuest(result.item, progress = null, failed = true)
                notify(Messages.notSent(result.message ?: Messages.failureReason(result.kind, to)))
                if (result.kind == RelayErrorKind.UNAUTHORIZED) recoverUnauthorized(token)
            }
        }
    }

    // ---- guest room ---------------------------------------------------------------------------------------------

    internal fun onGuestRoomEvent(e: RelayEvent) {
        val room = guestRoom.state.value as? GuestRoomUi.Open
        guestRoom.onEvent(e)
        if (e is RelayEvent.GuestJoined && room != null && room.roomId == e.roomId && room.guest == null) notify("Guest joined")
    }

    private fun isGuest(deviceId: String) = (guestRoom.state.value as? GuestRoomUi.Open)?.guest?.deviceId == deviceId

    /**
     * Shows [item] (sent to the guest) in the room's exchange list: one row with its progress while uploading, then one
     * row per top-level file or folder. The first row keeps the progress row's id, so it replaces it. With [failed] the
     * first row says so instead of staying at its last progress.
     */
    private fun recordSentToGuest(item: OutgoingFiles, progress: Pair<Long, Long>?, failed: Boolean = false) {
        val units = item.files.groupBy { topFolder(it.relativePath) ?: it.relativePath }.entries.toList()
        if (failed) {
            guestRoom.record(Exchanged("out:${item.id}:0", false, units.first().key, "Couldn't send", null, false, null))
            return
        }
        if (progress != null) {
            val (done, total) = progress
            val fraction = if (total > 0) done.toFloat() / total else 1f
            val (title, _) = units.first()
            guestRoom.record(Exchanged("out:${item.id}:0", false, title, "${Messages.size(done)} of ${Messages.size(total)} · ${(fraction * 100).toInt()}%", fraction, false, null))
            return
        }
        units.forEachIndexed { i, (title, files) ->
            val bytes = files.sumOf { it.sizeBytes }
            val detail = if (files.first().relativePath.contains('/')) "Folder · ${files.size} ${if (files.size == 1) "file" else "files"} · ${Messages.size(bytes)}" else Messages.size(bytes)
            guestRoom.recordSent("out:${item.id}:$i", title, detail, files.mapNotNull { it.ref?.id }.toSet())
        }
    }

    /** Sends files and folders to the guest of the open room through the normal outbox; with no guest yet, nothing is sent. */
    fun sendToGuest(files: List<File>): Job = scope.launch {
        val guest = (guestRoom.state.value as? GuestRoomUi.Open)?.guest ?: return@launch
        val scan = withContext(Dispatchers.IO) { scanFiles(files) }
        // The queue's own rules (duplicates, size limit) apply, but to this send alone, not to what is queued on the Send tab.
        val staged = SendState().withScanned(scan)
        staged.notice?.let { notify(it) }
        if (staged.files.isEmpty()) return@launch
        val result = send(guest, emptyList(), staged.files)
        if (result.message.isNotBlank()) notify(result.message)
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
     * Sends the checked links and all files of the queue to the chosen target, in [scope]. Afterwards the sent links
     * and files leave the queue; unchecked links stay.
     */
    fun sendSelected(): Job {
        var started: SendState? = null
        _send.update { s ->
            // Atomic with the flag, so two clicks cannot both start (the lambda may rerun: always reassign).
            started = if (s.sending || s.target == null || !s.canSend) null else s
            if (started != null) s.copy(sending = true) else s
        }
        val s = started ?: return Job().apply { complete() }
        val target = s.target!!
        val tabs = s.tabs()
        val files = s.selectedFiles
        return scope.launch {
            try {
                val r = try {
                    send(target, tabs, files)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) { // a local failure, e.g. writing outbox.json
                    SendResult(Messages.notSent(Messages.failureReason(RelayErrorKind.SERVER, nicknames.displayName(target))), emptyList(), false)
                }
                val sentUrls = r.sentTabs.map { it.url }.toSet()
                updateSend { cur ->
                    val kept = if (r.filesQueued) cur.copy(files = cur.files - files.toSet(), selected = cur.selected - files.mapTo(HashSet()) { uploadUnitKey(it) }) else cur
                    kept.withoutLinks(sentUrls).withoutStaleRow().copy(notice = r.message)
                }
                // Only the Send tab shows the notice itself; any other tab, or no window, gets a tray notification.
                if (!(mainWindowVisible && mainWindowTab == MainTab.SEND)) notify(r.message)
            } finally {
                updateSend { it.copy(sending = false) }
            }
        }
    }

    /** Explorer's *Send to* / a second launch: queues [paths] (scanned off the UI thread), aims at the last target, shows the Send tab. */
    fun handOff(paths: List<File>): Job = scope.launch {
        val scan = withContext(Dispatchers.IO) { scanFiles(paths) }
        updateSend { s ->
            val last = _config.value.lastPhoneId
            (if (last != null) s.selectTarget(last, _config.value.lastSpaceId) else s).withScanned(scan)
        }
        openWindow(MainTab.SEND)
    }

    /**
     * A later launch (Start menu, or *Send to*): with [paths], queues them; with none, just shows the window on [lastTab].
     * Returns the queueing job, or null when there was nothing to queue.
     */
    fun onLaunchedAgain(paths: List<File>, lastTab: MainTab): Job? {
        if (paths.isEmpty()) { openWindow(lastTab); return null }
        return handOff(paths)
    }

    /** Sends links now and queues files; returns the message to show. */
    suspend fun send(target: Peer, tabs: List<Tab>, files: List<LocalUpload>): SendResult {
        val shown = nicknames.displayName(target)
        val api = api() ?: return SendResult(Messages.notSent(Messages.failureReason(RelayErrorKind.NETWORK, shown)), emptyList(), false)
        val token = store.read().token
        val parts = mutableListOf<String>()
        var sentTabs = emptyList<Tab>()
        var error: RelayException? = null
        if (tabs.isNotEmpty()) {
            val outcome = sendInBatches(api, target.deviceId, tabs, spaceId = target.space?.id)
            sentTabs = outcome.sentTabs
            error = outcome.error
            if (sentTabs.isNotEmpty()) parts += Messages.sent(sentTabs.size, 0, shown, target.space?.name)
            if (sentTabs.isNotEmpty() && isGuest(target.deviceId)) guestRoom.recordLinks(System.nanoTime().toString(), sentTabs)
        }
        if (files.isNotEmpty()) {
            val now = System.currentTimeMillis()
            for (group in groupForSending(files)) {
                outbox.enqueue(OutgoingFiles(UUID.randomUUID().toString(), target.deviceId, target.name, group, createdAtEpochMs = now, spaceId = target.space?.id, spaceName = target.space?.name))
            }
            parts += Messages.queued(files.size, shown)
            kickOutbox()
        }
        if (error != null) {
            parts += Messages.notSent(Messages.failureReason(error, shown))
            if (error.kind == RelayErrorKind.UNAUTHORIZED) scope.launch { recoverUnauthorized(token) }
        }
        // A guest is not a device to come back to: it never becomes the last-used (default) target.
        if (target.kind != DeviceKind.GUEST && (sentTabs.isNotEmpty() || files.isNotEmpty())) updateConfig { it.copy(lastPhoneId = target.deviceId, lastSpaceId = target.space?.id) }
        return SendResult(parts.joinToString(" "), sentTabs, files.isNotEmpty())
    }

    /** Tray action: sends the links found in the clipboard to the last device. */
    fun sendClipboard() {
        scope.launch {
            val targetId = _config.value.lastPhoneId ?: return@launch // the last target, PC or phone
            val target = lastTargetPeer(_peers.value, targetId, _config.value.lastSpaceId) ?: Peer(targetId, lastPhoneName(), app.mjolnir.protocol.DeviceKind.PC)
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
        _peers.value.find { it.deviceId == _config.value.lastPhoneId }?.let(nicknames::displayName) ?: "device"

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
                    codeUsedUp(request)
                }
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            // The dialog is gone, so a failed accept can't be retried; the claim used the code up.
            if (accept && e.kind != RelayErrorKind.UNAUTHORIZED) codeUsedUp(request)
            notify(spaceErrorCopy(e) ?: couldntAnswer(Messages.failureReason(e, request.from.name)))
            return
        }
        request.space?.let { space ->
            // Joining a space is not a pairing: the joiner becomes a member, not the last-used device.
            spaces.onJoinRequestHandled(space.id, accepted = true)
            refreshAfterSpaceChange()
            return
        }
        if (_config.value.lastPhoneId == null) updateConfig { it.copy(lastPhoneId = request.from.deviceId) }
        refreshPeers()
        val name = request.from.name
        if (_pairedWith.subscriptionCount.value > 0) _pairedWith.emit(name) else notify("Paired with $name")
    }

    /** The request's code is used up without a joiner: the pair window, or the space's invite, shows a fresh one. */
    private fun codeUsedUp(request: RelayEvent.PairRequested) {
        val space = request.space
        if (space == null) _codeUsedUp.tryEmit(Unit) else spaces.onJoinRequestHandled(space.id, accepted = false)
    }

    /** A space event: the peer list changed, and with it who still has a nickname worth keeping. */
    internal fun onSpaceDeleted(space: SpaceRef) {
        if (spaces.onSpaceDeleted(space)) notify("${space.name} was deleted.")
        scope.launch { refreshAfterSpaceChange() }
    }

    /** Refreshes the peer list, then forgets nicknames of devices nothing links you to any more (no pairing, no shared space). */
    internal suspend fun refreshAfterSpaceChange() {
        if (!refreshPeers()) return // a stale or empty list must not cost anyone their nickname
        val linked = _peers.value.mapTo(HashSet()) { it.deviceId }
        nicknames.changes.value.keys.filter { it !in linked }.forEach(nicknames::remove)
    }

    fun startUnpair(peer: Peer): Job = scope.launch { unpair(peer) }

    suspend fun unpair(peer: Peer) {
        val api = api() ?: return
        try {
            api.unpair(peer.deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            notify(Messages.failureReason(e, nicknames.displayName(peer)))
        }
        // The refreshed list shows whether a shared space still links the device: /v1/peers lists it once, as paired, while both hold.
        refreshAfterSpaceChange()
    }

    /** Switches relay in [scope]: forgets credentials and pairings, then registers on the new one. */
    fun changeRelay(url: String): Job = scope.launch { switchRelay(url) }

    private suspend fun switchRelay(url: String) = relaySwitch.withLock {
        if (RelayUrls.normalize(url) == RelayUrls.normalize(_config.value.relayUrl)) return@withLock
        serviceSwitch.withLock { serviceJob?.cancelAndJoin(); serviceJob = null }
        connected.value = false
        updateConfig { it.copy(relayUrl = url, deviceId = null, token = null, lastPhoneId = null, lastSpaceId = null) }
        nicknames.clear() // every pairing is gone
        spaces.clear()
        clearChats()
        setPeers(emptyList())
        restartService()
    }

    private val relaySwitch = Mutex()

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
                return Messages.failureReason(e, trimmed)
            }
        }
        updateConfig { it.copy(deviceName = trimmed) }
        return null
    }

    /**
     * Applies the Settings draft in [scope], so closing the window cannot interrupt it; [onResult] gets an error
     * message, or null when everything was applied.
     */
    fun saveSettings(draft: SettingsDraft, onResult: (String?) -> Unit = {}): Job = scope.launch { onResult(applySettings(draft)) }

    /**
     * Applies [draft]: the device name (on the relay too, while registered), folder and checkboxes, the two Windows
     * settings (stored only when they took effect), and last the relay, which unpairs everything. Returns a message
     * for an invalid draft or a setting that could not be applied, else null.
     */
    suspend fun applySettings(draft: SettingsDraft): String? {
        draft.validate()?.let { return it }
        val current = _config.value
        val name = draft.deviceName.trim()
        // Switching relay re-registers under the stored name, so the old relay (maybe down) is not asked to rename.
        val switching = draft.relayChanged(current) || current.relayUrl.isBlank()
        if (name != current.deviceName) {
            if (switching) updateConfig { it.copy(deviceName = name) } else rename(name)?.let { return it }
        }
        val dir = draft.saveDir.trim()
        val saveDir = when {
            dir.isEmpty() -> null
            dir == current.saveDirPath().absolutePath -> current.saveDir // the shown default stays "default"
            else -> dir
        }
        updateConfig { it.copy(saveDir = saveDir, openLinksOnArrival = draft.openLinksOnArrival, keepInTray = draft.keepInTray) }
        var problem: String? = null
        if (draft.launchAtLogin != current.launchAtLogin) {
            if (launchAtLoginSetter(draft.launchAtLogin)) updateConfig { it.copy(launchAtLogin = draft.launchAtLogin) }
            else problem = "Couldn't change whether Mjolnir starts when you sign in."
        }
        if (draft.sendToMenu != current.sendToMenu) {
            if (sendToSetter(draft.sendToMenu)) updateConfig { it.copy(sendToMenu = draft.sendToMenu) }
            else problem = problem ?: "Couldn't change Explorer's Send to menu."
        }
        if (switching) RelayUrls.normalize(draft.relayUrl)?.let { switchRelay(it) }
        return problem
    }

    /** Recreates the *Send to* shortcut at startup when the setting is on (repairs a deleted or moved one). */
    fun ensureSendToShortcut(): Job? =
        if (_config.value.sendToMenu) scope.launch { sendToSetter(true) } else null

    /** Opens the folder holding the bundled font licences (extracted from the jar on first use). */
    fun openLicences() {
        scope.launch {
            try {
                Desktop.getDesktop().open(extractLicences(File(dataDir, "licences")))
            } catch (e: Exception) {
                // nothing sensible to do
            }
        }
    }

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
