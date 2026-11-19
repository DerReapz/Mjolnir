package app.mjolnir.android

import app.mjolnir.android.session.resetQuietly
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mjolnir.android.send.scheduleOutgoing
import app.mjolnir.android.share.UriImporter
import app.mjolnir.android.share.tabsFromShare
import app.mjolnir.android.receive.EXTRA_SCREEN
import app.mjolnir.android.receive.SCREEN_CAUGHT
import app.mjolnir.android.receive.enqueueInboxDrain
import app.mjolnir.android.receive.syncPushToken
import app.mjolnir.android.ui.CaughtScreen
import app.mjolnir.android.ui.CaughtViewModel
import app.mjolnir.android.ui.DevicesScreen
import app.mjolnir.android.ui.DevicesViewModel
import app.mjolnir.android.ui.MjolnirNav
import app.mjolnir.android.ui.PairingViewModel
import app.mjolnir.android.ui.Screen
import app.mjolnir.android.ui.SettingsScreen
import app.mjolnir.android.ui.SettingsViewModel
import app.mjolnir.android.ui.ThrowScreen
import app.mjolnir.android.ui.ThrowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val container get() = (application as MjolnirApp).container

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val c = container
            val session = c.session
            return when (modelClass) {
                ThrowViewModel::class.java -> {
                    val appContext = applicationContext
                    ThrowViewModel(
                        c.api, c.tray, c.config, c.outbox, scheduleOutgoing = { id -> scheduleOutgoing(appContext, id) },
                        io = Dispatchers.IO, onUnauthorized = { resetQuietly(session) },
                    )
                }
                DevicesViewModel::class.java -> DevicesViewModel(c.api, session)
                PairingViewModel::class.java -> PairingViewModel(c.api, c.config, onUnauthorized = { session.reset() })
                CaughtViewModel::class.java -> CaughtViewModel(c.caught, c.caughtChanged, Dispatchers.IO)
                SettingsViewModel::class.java -> SettingsViewModel(c.api, c.config, session, onRelayUrlChanged = c::invalidateRelayClient)
                else -> error("Unknown ViewModel $modelClass")
            } as T
        }
    }

    private val throwVm: ThrowViewModel by viewModels { factory }
    private val importer by lazy { UriImporter(applicationContext, container.outboxDir) } // never captures the Activity

    private var requestedScreen by mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForNotifications()
        if (savedInstanceState == null) {
            handleShare(intent) // a recreated activity must not import the same share twice
            handleScreen(intent)
        }
        setContent {
            MaterialTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.THROW) }
                LaunchedEffect(requestedScreen) {
                    requestedScreen?.let { screen = it; requestedScreen = null }
                }
                Scaffold(bottomBar = { MjolnirNav(screen, onSelect = { screen = it }) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (screen) {
                            Screen.THROW -> ThrowScreen(throwVm, importer, onPairDevice = { screen = Screen.DEVICES })
                            Screen.CAUGHT -> CaughtScreen(viewModel(factory = factory))
                            Screen.DEVICES -> DevicesScreen(viewModel(factory = factory), viewModel(factory = factory))
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

    /** A Caught notification opens the Caught screen. */
    private fun handleScreen(intent: Intent) {
        if (intent.getStringExtra(EXTRA_SCREEN) != SCREEN_CAUGHT) return
        intent.removeExtra(EXTRA_SCREEN)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        requestedScreen = Screen.CAUGHT
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            try {
                container.session.ensureRegistered()
                syncPushToken(applicationContext)
            } catch (_: Exception) { }
            enqueueInboxDrain(applicationContext) // pick up anything the push didn't announce (or no Firebase)
            throwVm.refreshTargets()
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
        throwVm.importShared(tabsFromShare(texts, subject), if (uris.isEmpty()) null else ({ importer.importUris(uris) }))
        requestedScreen = Screen.THROW
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
