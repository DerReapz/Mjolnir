package app.mjolnir.android

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import app.mjolnir.BuildConfig
import androidx.work.ExistingWorkPolicy
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.android.data.TrayItem
import app.mjolnir.android.data.CaughtItem
import app.mjolnir.android.receive.createReceivingChannels
import app.mjolnir.android.receive.schedulePeriodicInboxDrain
import app.mjolnir.android.receive.syncPushToken
import app.mjolnir.android.send.createSendingChannels
import app.mjolnir.android.send.scheduleOutgoing
import app.mjolnir.android.session.PhoneSession
import app.mjolnir.android.session.SwitchingRelayApi
import android.net.ConnectivityManager
import android.net.Network
import app.mjolnir.android.chat.ChatSendFiles
import app.mjolnir.android.chat.createChatSendingChannel
import app.mjolnir.android.chat.ExoVideoPlayer
import app.mjolnir.android.chat.chatVideoHttpClient
import app.mjolnir.android.ui.VideoPlayerState
import app.mjolnir.android.chat.enqueueChatSends
import app.mjolnir.android.chat.scheduleChatSendsWhenWaiting
import app.mjolnir.client.ChatController
import app.mjolnir.client.ChatMediaCache
import app.mjolnir.client.ChatOutbox
import kotlinx.coroutines.launch
import app.mjolnir.client.ChatStore
import app.mjolnir.client.FileSeenDeliveries
import app.mjolnir.client.GuestRoomController
import app.mjolnir.client.guestRoomSecretStore
import app.mjolnir.android.ui.GuestTraffic
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Nicknames
import app.mjolnir.client.Outbox
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayClient
import app.mjolnir.client.Platform
import app.mjolnir.client.Updater
import app.mjolnir.client.newMjolnirHttpClient
import app.mjolnir.android.update.ApkInstaller
import app.mjolnir.android.update.UpdateFlow
import app.mjolnir.android.update.forwardRelayVersion
import app.mjolnir.android.update.forwardingUpdates
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer

class MjolnirApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    /** For short app-wide jobs that must outlive a screen or service (e.g. giving the relay a new push token). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createSendingChannels(this)
        createReceivingChannels(this)
        createChatChannel(this)
        createChatSendingChannel(this)
        schedulePeriodicInboxDrain(this)
        syncPushToken(this)
        resumeChatSendsOnNetwork()
        appScope.launch {
            container.updater.cleanOld() // an APK left by an earlier run (the update it installed, or one never installed)
            container.updater.start() // checks now and every 6 hours; installs only when the user taps Install
        }
        // Throws still in the outbox (e.g. after a force-stop or reboot) get their work back; KEEP leaves running
        // or waiting work alone.
        Thread {
            try {
                for (item in container.outbox.pending()) scheduleOutgoing(this, item.id, policy = ExistingWorkPolicy.KEEP)
            } catch (e: IOException) {
                // The outbox couldn't be read; the next start tries again.
            }
        }.start()
    }
}

/** Ruling CM-R2: sends with files that stopped for want of a network go on as soon as one is back (and once at start). */
private fun MjolnirApp.resumeChatSendsOnNetwork() {
    try {
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { container.chatController.resumeSends() }
        })
    } catch (e: RuntimeException) {
        // Too many callbacks or no permission: sends still resume in the foreground and on every reconnect.
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir

    val config = JsonFileStore(File(filesDir, "config.json"), serializer<PhoneConfig>()) {
        PhoneConfig(relayUrl = BuildConfig.RELAY_URL, deviceName = Build.MODEL)
    }
    val tray = JsonFileStore(File(filesDir, "tray.json"), ListSerializer(serializer<TrayItem>())) { emptyList() }
    /** Private, local names for paired devices; never sent anywhere. */
    val nicknames = Nicknames(JsonFileStore(File(filesDir, "nicknames.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() })
    val outboxDir = File(filesDir, "outbox")
    val seen = FileSeenDeliveries(File(filesDir, "seen.json"))

    /** What was received, newest first (at most [app.mjolnir.android.receive.MAX_CAUGHT_ITEMS]). */
    val caught = JsonFileStore(File(filesDir, "caught.json"), ListSerializer(serializer<CaughtItem>())) { emptyList() }

    /** Bumped after each inbox drain so an open Caught screen reloads [caught]. */
    val caughtChanged = MutableStateFlow(0L)

    /** Downloads in progress (`<file id>.part`); kept across restarts so they resume. */
    val stagingDir = File(filesDir, "incoming")

    /** Delivery ids the user chose to download on mobile data (`Download now`). */
    val allowedOnce = JsonFileStore(File(filesDir, "allowed-metered.json"), SetSerializer(String.serializer())) { emptySet() }

    /** Held by every inbox drain, so two workers never receive the same delivery at once. */
    val inboxLock = Mutex()

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * App updates from the project's GitHub releases. Its HTTP client is its own, never a relay client's, so the relay's
     * bearer token and the app/relay version headers never reach GitHub. Before [api]: every relay client feeds it.
     */
    val updater = Updater(
        newMjolnirHttpClient(), Platform.ANDROID_APK, BuildConfig.VERSION_NAME, File(appContext.cacheDir, "updates"), updateScope,
    )

    /** Talks to the relay at the current `relayUrl`; the client is rebuilt when the URL changes and reads the token live. */
    val api: RelayApi = SwitchingRelayApi(::currentClient)

    /** Downloaded chat photos and files (an LRU cache of 512 MB in the app's cache folder); bubbles pin what they show. */
    val chatMediaCache = ChatMediaCache(File(appContext.cacheDir, "chat-media"))

    /**
     * The app's one chat video player (ExoPlayer, made on first play and freed when no bubble shows a video), streaming
     * from the relay with the bearer token. Used from the main thread only.
     */
    val chatVideos: VideoPlayerState by lazy {
        val http = chatVideoHttpClient(api)
        VideoPlayerState { ExoVideoPlayer(appContext, http) }
    }

    /** The app's copies of files being sent to a chat (a picked file's grant is temporary; the send outlives the process). */
    val chatSendFiles = ChatSendFiles(File(filesDir, "chat-send"))

    /** Chat files staged for Download and Share…, offered through the FileProvider. */
    val chatExportDir = File(appContext.cacheDir, "chat-export")

    /**
     * Everything about the chats: lists, messages (cached in `chats/`), unread counts, and sends with files (kept in
     * `chat-outbox/` so they resume after the process dies). App-wide, so background work can sync, send and notify.
     */
    val chatController = ChatController(
        api = { api }, store = ChatStore(File(filesDir, "chats").also { it.mkdirs() }),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        outbox = ChatOutbox(File(filesDir, "chat-outbox")), media = chatMediaCache,
        me = { try { config.read().deviceId } catch (e: IOException) { null } },
    )

    init {
        // A send's copies go once no pending send needs them (posted, dismissed, or everything reset); also at start.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { chatController.pendingSends.collect { chatSendFiles.prune(it) } }
        // Ruling CM-R6: sends waiting (left over at start, or just queued) also get their own background work.
        scheduleChatSendsWhenWaiting(chatController.pendingSends, CoroutineScope(SupervisorJob() + Dispatchers.IO)) { enqueueChatSends(appContext) }
    }

    /** Always on, and started before any refresh: with no collector a new message would be dropped, not notified. */
    val chatNotifier = ChatNotifier(
        chatController, CoroutineScope(SupervisorJob() + Dispatchers.IO), nicknameOf = nicknames::get,
        post = { m, name -> postChatNotification(appContext, m, name) },
    )

    /** True while an activity of the app is started. */
    val appForeground = MutableStateFlow(false)

    /** True while the Chats tab is the screen shown. */
    val chatsVisible = MutableStateFlow(false)

    /** The update banner and its Install button; the install prompt opens only while the app is in the foreground. */
    val updateFlow = UpdateFlow(updater, ApkInstaller.forApp(appContext), updateScope, appForeground)

    /** Live chat updates while the Chats tab is on screen. */
    val chatWatcher = ChatWatcher(
        chatController, events = { api.events().forwardingUpdates(updater) }, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        active = combine(appForeground, chatsVisible) { fg, shown -> fg && shown },
    )

    /** The one registration guard for the whole app. */
    val session = PhoneSession(api, config, onReset = { nicknames.clear(); chatController.clear(); chatExportDir.deleteRecursively() })

    /** Call after the relay URL was written to [config]; the next call builds a client for the new address. */
    fun invalidateRelayClient() = synchronized(clientLock) { cachedUrl = null }

    val outbox = Outbox(
        store = JsonFileStore(File(filesDir, "outbox.json"), ListSerializer(serializer<OutgoingFiles>())) { emptyList() },
        api = { api },
        open = ::openUpload,
        cleanup = ::cleanupUpload,
    )

    /** The guest room this phone owns; its rows and state live here so background work can record into it. */
    val guestRoom = GuestRoomController(
        api = { api },
        relayUrl = { config.read().relayUrl },
        secrets = guestRoomSecretStore(File(filesDir, "guest-room.json")),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = System::currentTimeMillis,
    )
    val guestTraffic = GuestTraffic(guestRoom)

    /** Listens to the relay while a room is open, on any screen: marks downloads and notifies when the guest joins. */
    val guestRoomWatcher = GuestRoomWatcher(
        guestRoom, events = { api.events().forwardingUpdates(updater) }, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onGuestJoined = { postGuestJoinedNotification(appContext) },
    )

    private fun openUpload(upload: LocalUpload, offset: Long): InputStream {
        if (upload.path.startsWith("content://")) {
            val input = appContext.contentResolver.openInputStream(Uri.parse(upload.path))
                ?: throw java.io.FileNotFoundException(upload.path)
            try {
                var left = offset
                while (left > 0) {
                    val skipped = input.skip(left)
                    if (skipped <= 0) { // skip() may refuse; fall back to reading
                        if (input.read() < 0) break
                        left--
                    } else left -= skipped
                }
            } catch (e: Exception) {
                input.close()
                throw e
            }
            return input
        }
        return FileInputStream(upload.path).also { input ->
            try {
                input.channel.position(offset)
            } catch (e: Exception) {
                input.close()
                throw e
            }
        }
    }

    /** Deletes the app's own copy; a user's content:// file or any path outside the outbox directory is never touched. */
    private fun cleanupUpload(upload: LocalUpload) {
        if (upload.path.startsWith("content://")) return
        val file = File(upload.path)
        val dir = outboxDir.canonicalFile
        val parent = file.canonicalFile.parentFile
        if (parent != null && (parent == dir || parent.path.startsWith(dir.path + File.separator))) file.delete()
    }

    private val clientLock = Any()
    private var cachedUrl: String? = null
    private var client: RelayClient? = null
    private var relayVersionJob: Job? = null

    /** Cached so calls never read config.json; rebuilt (old client closed) after [invalidateRelayClient]. */
    private fun currentClient(): RelayApi = synchronized(clientLock) {
        val existing = client
        if (existing != null && cachedUrl != null) return existing
        val url = config.read().relayUrl
        existing?.close()
        RelayClient.create(url, BuildConfig.VERSION_NAME) { config.read().token }.also { c ->
            client = c
            cachedUrl = url
            // The relay's version header (any response): a relay older than the app is flagged, a newer one means an
            // update is out (ruling UP-R3: no socket of its own for the relay's UpdateAvailable push).
            relayVersionJob?.cancel()
            relayVersionJob = updateScope.launch {
                c.relayVersion.filterNotNull().collect { forwardRelayVersion(updater, BuildConfig.VERSION_NAME, it) }
            }
        }
    }

    // Last, so everything the relay client needs exists before the restore runs on its own thread.
    init { guestRoom.restore() } // a room still open on the relay is shown (and listened to) from app start
}
