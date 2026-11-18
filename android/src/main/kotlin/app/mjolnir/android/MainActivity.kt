package app.mjolnir.android

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
import app.mjolnir.android.share.Imported
import app.mjolnir.android.share.UriImporter
import app.mjolnir.android.share.tabsFromShare
import app.mjolnir.android.data.TrayItem
import app.mjolnir.android.throwing.Sensitivity
import app.mjolnir.android.ui.CaughtPlaceholder
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
                ThrowViewModel::class.java ->
                    // Task 19 wires the background worker here; until then queued files are only stored in the outbox.
                    ThrowViewModel(c.api, c.tray, c.config, c.outbox, scheduleOutgoing = { }, io = Dispatchers.IO)
                DevicesViewModel::class.java -> DevicesViewModel(c.api, session)
                PairingViewModel::class.java -> PairingViewModel(c.api, c.config, onUnauthorized = { session.reset() })
                SettingsViewModel::class.java -> SettingsViewModel(c.api, c.config, session, onRelayUrlChanged = c::invalidateRelayClient)
                else -> error("Unknown ViewModel $modelClass")
            } as T
        }
    }

    private val throwVm: ThrowViewModel by viewModels { factory }
    private val importer by lazy { UriImporter(this, container.outboxDir) }

    private var requestedScreen by mutableStateOf<Screen?>(null)
    private var sensitivity by mutableStateOf(Sensitivity.MEDIUM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForNotifications()
        if (savedInstanceState == null) handleShare(intent) // a recreated activity must not import the same share twice
        setContent {
            MaterialTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.THROW) }
                LaunchedEffect(requestedScreen) {
                    requestedScreen?.let { screen = it; requestedScreen = null }
                }
                Scaffold(bottomBar = { MjolnirNav(screen, onSelect = { screen = it }) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (screen) {
                            Screen.THROW -> ThrowScreen(throwVm, importer, sensitivity, onPairPc = { screen = Screen.DEVICES })
                            Screen.CAUGHT -> CaughtPlaceholder() // replaced when the receive task lands
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
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            try { container.session.ensureRegistered() } catch (_: Exception) { }
            throwVm.refreshPcs()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            sensitivity = try { withContext(Dispatchers.IO) { container.config.read().sensitivity } } catch (_: Exception) { sensitivity }
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
        val texts = if (action == Intent.ACTION_SEND) {
            listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        } else {
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.map { it.toString() }.orEmpty()
        }
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        val uris = streamUris(intent, action)
        intent.action = null // handled; a re-delivery of this intent must not import again

        lifecycleScope.launch {
            val tabs = tabsFromShare(texts, subject)
            val files: Imported = if (uris.isEmpty()) Imported(emptyList(), emptyList())
            else withContext(Dispatchers.IO) { importer.importUris(uris) }
            val items: List<TrayItem> = tabs.map { TrayItem.Link(it) } + files.items
            // A share of only files that all failed already carries notices; "no links" is only for text-only shares.
            val notices = files.notices
            throwVm.addShared(items, if (items.isEmpty() && uris.isEmpty()) emptyList() else notices)
            requestedScreen = Screen.THROW
        }
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
