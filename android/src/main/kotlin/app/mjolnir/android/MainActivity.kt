package app.mjolnir.android

import app.mjolnir.android.session.resetQuietly
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import app.mjolnir.android.ui.InVoiceBar
import app.mjolnir.android.ui.VoiceEffect
import app.mjolnir.android.ui.VoiceViewModel
import app.mjolnir.android.voice.startVoiceService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import app.mjolnir.android.ui.Forge
import app.mjolnir.android.ui.MjolnirTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mjolnir.android.send.scheduleOutgoing
import app.mjolnir.android.share.UriImporter
import app.mjolnir.android.share.tabsFromShare
import app.mjolnir.android.receive.EXTRA_SCREEN
import app.mjolnir.android.receive.SCREEN_CAUGHT
import app.mjolnir.android.receive.enqueueInboxDrain
import app.mjolnir.android.receive.syncPushToken
import app.mjolnir.android.receive.EXTRA_CHAT
import app.mjolnir.android.receive.SCREEN_CHATS
import app.mjolnir.android.ui.CaughtScreen
import app.mjolnir.android.chat.AndroidThumbnailer
import app.mjolnir.android.chat.ChatMediaSupport
import app.mjolnir.android.receive.MediaStoreFilePublisher
import app.mjolnir.android.ui.ChatViewModel
import app.mjolnir.android.ui.ShareToChatDialog
import app.mjolnir.android.ui.SharedContent
import app.mjolnir.android.ui.ChatsScreen
import app.mjolnir.android.ui.CaughtViewModel
import app.mjolnir.android.ui.DevicesScreen
import app.mjolnir.android.ui.DevicesViewModel
import app.mjolnir.android.ui.GuestRoomViewModel
import app.mjolnir.android.ui.MjolnirNav
import app.mjolnir.android.ui.UpdateBanner
import app.mjolnir.android.ui.PairingViewModel
import app.mjolnir.android.ui.Screen
import app.mjolnir.android.ui.SettingsScreen
import app.mjolnir.android.ui.SettingsViewModel
import app.mjolnir.android.ui.SpacesViewModel
import app.mjolnir.android.ui.ThrowScreen
import app.mjolnir.android.ui.ThrowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val container get() = (application as MjolnirApp).container

    private val factory = object : ViewModelProvider.Factory {
        /** The Spaces view model keeps its invite code in the saved state, which only the extras can reach. */
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            if (modelClass == ChatViewModel::class.java) {
                val c = container
                val appContext = applicationContext
                val chatImporter = UriImporter(appContext, c.outboxDir) // never captures the Activity
                val media = ChatMediaSupport(
                    c.chatMediaCache, AndroidThumbnailer, MediaStoreFilePublisher(appContext), c.chatSendFiles,
                    importer = { uris, dir -> chatImporter.importForChat(uris.map(Uri::parse), dir) }, exportDir = c.chatExportDir,
                )
                val voice = voiceVm // in the same store: holding it (not the Activity) is safe
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(
                    c.chatController, c.api, c.nicknames, { try { c.config.read().deviceId } catch (e: java.io.IOException) { null } },
                    c.appForeground, c.chatsVisible, extras.createSavedStateHandle(),
                    onRead = { chatId -> cancelChatNotification(appContext, chatId) }, media = media, videos = c.chatVideos,
                    onSpacesLoaded = voice::spacesLoaded,
                ) as T
            }
            if (modelClass != SpacesViewModel::class.java) return create(modelClass)
            val c = container
            val throwing = throwVm
            val voice = voiceVm
            @Suppress("UNCHECKED_CAST")
            return SpacesViewModel(
                c.api, c.nicknames, extras.createSavedStateHandle(),
                onPeersAffected = { throwing.refreshTargets() }, onUnauthorized = { resetQuietly(c.session) },
                presence = c.chatController.presence, onSpacesLoaded = { all -> c.chatController.seedPresence(all); voice.spacesLoaded(all) },
            ) as T
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val c = container
            val session = c.session
            return when (modelClass) {
                ThrowViewModel::class.java -> {
                    val appContext = applicationContext
                    ThrowViewModel(
                        c.api, c.tray, c.config, c.outbox, c.nicknames, scheduleOutgoing = { id -> scheduleOutgoing(appContext, id) },
                        io = Dispatchers.IO, onUnauthorized = { resetQuietly(session) },
                        guestRoom = c.guestRoom.state, onLinksSentToGuest = c.guestTraffic::recordLinks,
                    )
                }
                DevicesViewModel::class.java -> DevicesViewModel(c.api, session, c.nicknames)
                PairingViewModel::class.java -> {
                    // The ThrowViewModel lives in the same store, so holding it (not the Activity) is safe.
                    val throwing = throwVm
                    PairingViewModel(c.api, c.config, onUnauthorized = { session.reset() }, onPaired = { throwing.refreshTargets() })
                }
                CaughtViewModel::class.java -> CaughtViewModel(c.caught, c.caughtChanged, Dispatchers.IO, c.nicknames)
                GuestRoomViewModel::class.java -> GuestRoomViewModel(c.guestRoom)
                VoiceViewModel::class.java -> {
                    val appContext = applicationContext
                    VoiceViewModel(
                        c.voice, selfId = { try { c.config.read().deviceId } catch (e: java.io.IOException) { null } },
                        hasMicPermission = { appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                        startService = { room -> startVoiceService(appContext, room.name) }, serviceNotices = c.voiceServiceNotices,
                    )
                }
                SettingsViewModel::class.java -> SettingsViewModel(c.api, c.config, session, onRelayUrlChanged = c::invalidateRelayClient)
                else -> error("Unknown ViewModel $modelClass")
            } as T
        }
    }

    private val throwVm: ThrowViewModel by viewModels { factory }
    private val chatVm: ChatViewModel by viewModels { factory }
    private val voiceVm: VoiceViewModel by viewModels { factory }
    private val importer by lazy { UriImporter(applicationContext, container.outboxDir) } // never captures the Activity

    private var requestedScreen by mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        askForNotifications()
        if (savedInstanceState == null) {
            handleShare(intent) // a recreated activity must not import the same share twice
            handleScreen(intent)
        }
        setContent {
            MjolnirTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.THROW) }
                LaunchedEffect(requestedScreen) {
                    requestedScreen?.let { screen = it; requestedScreen = null }
                }
                // Voice rooms need the microphone: the answer goes to the voice view model, which then starts the service and joins.
                val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> voiceVm.onMicPermission(granted) }
                LaunchedEffect(Unit) {
                    voiceVm.effects.collect { effect ->
                        when (effect) { VoiceEffect.RequestMicPermission -> micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                    }
                }
                val voiceActive by container.voice.active.collectAsState()
                val chatUnread by container.chatController.unread.collectAsState()
                val chatOpen by chatVm.selectedId.collectAsState()
                val shareOffer by chatVm.shareOffer.collectAsState()
                shareOffer?.let { offer ->
                    ShareToChatDialog(
                        offer, onPick = { chatId -> chatVm.sendShare(chatId); screen = Screen.CHATS },
                        onThrow = { chatVm.throwShare(); screen = Screen.THROW }, onCancel = chatVm::cancelShare,
                    )
                }
                DisposableEffect(screen) {
                    container.chatsVisible.value = screen == Screen.CHATS
                    onDispose { container.chatsVisible.value = false }
                }
                val updateBanner by container.updateFlow.banner.collectAsState()
                Scaffold(
                    containerColor = Forge.Background,
                    topBar = {
                        Column {
                            UpdateBanner(updateBanner, onInstall = { container.updateFlow.install() })
                            // Wherever the voice panel (at the foot of the chat list) is out of sight: a tap goes back to it.
                            voiceActive?.let { a -> if (screen != Screen.CHATS || chatOpen != null) InVoiceBar(a.roomName, onOpen = { chatVm.close(); screen = Screen.CHATS }) }
                        }
                    },
                    // A conversation takes the whole screen, as in the mockup.
                    bottomBar = { if (!(screen == Screen.CHATS && chatOpen != null)) MjolnirNav(screen, onSelect = { screen = it }, chatsBadge = chatUnread.values.sum()) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (screen) {
                            Screen.THROW -> ThrowScreen(throwVm, importer, onPairDevice = { screen = Screen.DEVICES })
                            Screen.CHATS -> ChatsScreen(chatVm, voice = voiceVm, onOpenSettings = ::openAppSettings)
                            Screen.CAUGHT -> CaughtScreen(viewModel(factory = factory))
                            Screen.DEVICES -> DevicesScreen(viewModel(factory = factory), viewModel(factory = factory), viewModel(factory = factory), viewModel(factory = factory))
                            Screen.SETTINGS -> SettingsScreen(viewModel(factory = factory))
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        handleScreen(intent)
    }

    /** A Caught notification opens the Caught screen; a chat notification opens that chat. */
    private fun handleScreen(intent: Intent) {
        val screen = intent.getStringExtra(EXTRA_SCREEN)
        if (screen != SCREEN_CAUGHT && screen != SCREEN_CHATS) return
        val chat = intent.getStringExtra(EXTRA_CHAT)
        intent.removeExtra(EXTRA_SCREEN)
        intent.removeExtra(EXTRA_CHAT)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (screen == SCREEN_CHATS) {
            chat?.let(chatVm::open)
            requestedScreen = Screen.CHATS
        } else requestedScreen = Screen.CAUGHT
    }

    override fun onStart() {
        super.onStart()
        container.appForeground.value = true
        container.updateFlow.onForeground() // an install session dropped while away is let go
        container.chatController.resumeSends() // ruling CM-R2: sends with files go on when the app comes back
        lifecycleScope.launch {
            try {
                container.session.ensureRegistered()
                syncPushToken(applicationContext)
            } catch (_: Exception) { }
            container.guestRoom.restore() // a room still open on the relay comes back (its secret is stored)
            enqueueInboxDrain(applicationContext) // pick up anything the push didn't announce (or no Firebase)
            throwVm.refreshTargets()
        }
    }

    override fun onStop() {
        container.appForeground.value = false
        super.onStop()
    }

    /** The app's page in the system settings, where a refused permission can be turned on. */
    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) // result not needed
        }
    }

    /** Imports what another app shared. The copy happens now, while the temporary read grant on the URIs lasts. */
    private fun handleShare(intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        // Reopening from Recents re-delivers the original share; it was already imported.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val texts = if (action == Intent.ACTION_SEND) {
            listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        } else {
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.map { it.toString() }.orEmpty()
        }
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        val uris = streamUris(intent, action)
        intent.action = null // handled; a re-delivery of this intent must not import again

        // The view model owns the import, so rotating mid-copy neither loses the share nor orphans the copies.
        val importer = importer
        val throwing = throwVm // held by the chat view model's offer: never the Activity
        val tabs = tabsFromShare(texts, subject)
        val throwIt = { throwing.importShared(tabs, if (uris.isEmpty()) null else ({ importer.importUris(uris) })) }
        val text = texts.joinToString("\n").takeIf { it.isNotBlank() }
        requestedScreen = Screen.THROW
        // With a chat to write in, "Send to a chat" is offered first (the files are copied once a chat is picked).
        if (uris.isEmpty() && text == null) throwIt()
        else chatVm.offerShare(SharedContent(text, uris.map { it.toString() }, throwInstead = throwIt))
    }

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent, action: String): List<Uri> =
        if (action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            listOfNotNull(uri)
        } else {
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
        }
}
