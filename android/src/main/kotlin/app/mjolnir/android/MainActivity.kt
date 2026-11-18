package app.mjolnir.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mjolnir.android.session.PhoneSession
import app.mjolnir.android.ui.DevicesScreen
import app.mjolnir.android.ui.DevicesViewModel
import app.mjolnir.android.ui.PairingViewModel
import app.mjolnir.android.ui.SettingsScreen
import app.mjolnir.android.ui.SettingsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = (application as MjolnirApp).container
        val session = PhoneSession(c.api, c.config)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                DevicesViewModel::class.java -> DevicesViewModel(c.api, session)
                PairingViewModel::class.java -> PairingViewModel(c.api, c.config, onUnauthorized = { session.reset() })
                SettingsViewModel::class.java -> SettingsViewModel(c.api, c.config, session)
                else -> error("Unknown ViewModel $modelClass")
            } as T
        }
        setContent {
            MaterialTheme {
                LaunchedEffect(Unit) { try { session.ensureRegistered() } catch (_: Exception) { } }
                var tab by remember { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Devices") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings") })
                    }
                    if (tab == 0) {
                        DevicesScreen(viewModel(factory = factory), viewModel(factory = factory))
                    } else {
                        SettingsScreen(viewModel(factory = factory))
                    }
                }
            }
        }
    }
}
