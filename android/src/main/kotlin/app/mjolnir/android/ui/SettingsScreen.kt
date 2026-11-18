package app.mjolnir.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mjolnir.android.data.LargeFilePolicy
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.android.session.PhoneSession
import app.mjolnir.android.throwing.Sensitivity
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Messages
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.client.RelayUrls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUi(val config: PhoneConfig, val notice: String? = null)

class SettingsViewModel(
    private val api: RelayApi,
    private val config: JsonFileStore<PhoneConfig>,
    private val session: PhoneSession,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUi(config.read()))
    val state: StateFlow<SettingsUi> = _state.asStateFlow()

    private fun set(transform: (PhoneConfig) -> PhoneConfig) {
        _state.value = SettingsUi(config.update(transform), _state.value.notice)
    }

    fun setSensitivity(value: Sensitivity) = set { it.copy(sensitivity = value) }
    fun setPolicy(value: LargeFilePolicy) = set { it.copy(largeFilePolicy = value) }
    fun setThreshold(mb: Int) = set { it.copy(largeFileThresholdMb = mb) }

    /** Ignores an invalid address; a changed address resets the registration. */
    fun changeRelayUrl(input: String) {
        val url = RelayUrls.normalize(input) ?: return
        if (url == config.read().relayUrl) return
        config.update { it.copy(relayUrl = url) }
        guarded { session.reset() }
    }

    fun rename(input: String) {
        val name = input.trim()
        if (name.length !in 1..64) return
        set { it.copy(deviceName = name) }
        guarded { api.rename(name) }
    }

    private fun guarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _state.value = SettingsUi(config.read(), null)
            } catch (e: RelayException) {
                if (e.kind == RelayErrorKind.UNAUTHORIZED) {
                    try { session.reset() } catch (_: RelayException) { }
                    _state.value = SettingsUi(config.read(), Messages.RESET)
                } else {
                    _state.value = SettingsUi(config.read(), Messages.failureReason(e.kind, "the relay"))
                }
            }
        }
    }
}

private val THRESHOLDS = listOf(10, 25, 50, 100)

@Composable
fun SettingsScreen(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val config = state.config
    var url by remember { mutableStateOf(config.relayUrl) }
    var name by remember { mutableStateOf(config.deviceName) }
    val normalized = RelayUrls.normalize(url)
    val urlChanged = normalized != null && normalized != config.relayUrl
    val nameValid = name.trim().length in 1..64

    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Relay URL") },
            isError = normalized == null,
            supportingText = { Text(if (normalized == null) "Use an https:// address" else if (urlChanged) "Changing the relay unpairs all devices." else "") },
            singleLine = true,
        )
        if (urlChanged) Button(onClick = { vm.changeRelayUrl(url) }) { Text("Change relay") }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Device name") },
            isError = !nameValid,
            supportingText = { Text("1 to 64 characters") },
            singleLine = true,
        )
        if (nameValid && name.trim() != config.deviceName) Button(onClick = { vm.rename(name) }) { Text("Save name") }

        Text("Flick sensitivity", style = MaterialTheme.typography.titleMedium)
        Choices(
            options = listOf(Sensitivity.LOW to "Low", Sensitivity.MEDIUM to "Medium", Sensitivity.HIGH to "High"),
            selected = config.sensitivity,
            onSelect = vm::setSensitivity,
        )

        Text("Large files (over ${config.largeFileThresholdMb} MB)", style = MaterialTheme.typography.titleMedium)
        Choices(
            options = listOf(LargeFilePolicy.WAIT_FOR_WIFI to "Wait for Wi-Fi", LargeFilePolicy.USE_MOBILE_DATA to "Use mobile data"),
            selected = config.largeFilePolicy,
            onSelect = vm::setPolicy,
        )
        Choices(options = THRESHOLDS.map { it to "$it" }, selected = config.largeFileThresholdMb, onSelect = vm::setThreshold)
    }
}

@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        options.forEach { (value, label) ->
            Row(
                Modifier.selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(label, Modifier.padding(start = 8.dp))
            }
        }
    }
}
