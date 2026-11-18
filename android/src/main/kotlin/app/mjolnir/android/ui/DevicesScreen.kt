package app.mjolnir.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mjolnir.android.session.PhoneSession
import app.mjolnir.client.Messages
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.Peer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DevicesUi(val peers: List<Peer> = emptyList(), val notice: String? = null)

class DevicesViewModel(private val api: RelayApi, private val session: PhoneSession) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUi())
    val state: StateFlow<DevicesUi> = _state.asStateFlow()

    fun refresh() = run { _state.value = _state.value.copy(peers = api.peers(), notice = null) }

    fun unpair(deviceId: String) = run {
        api.unpair(deviceId)
        _state.value = _state.value.copy(peers = api.peers(), notice = null)
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: RelayException) {
                if (e.kind == RelayErrorKind.UNAUTHORIZED) {
                    try { session.reset() } catch (_: RelayException) { }
                    _state.value = DevicesUi(notice = Messages.RESET)
                } else {
                    _state.value = _state.value.copy(notice = Messages.failureReason(e.kind, "your PC"))
                }
            }
        }
    }
}

@Composable
fun DevicesScreen(vm: DevicesViewModel, pairing: PairingViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val pairingState by pairing.state.collectAsState()
    var showPairing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Peer?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(pairingState) { if (pairingState is PairingUi.Paired) vm.refresh() }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showPairing) {
            PairingFlow(pairing, pairingState) { pairing.restart(); showPairing = false }
        } else {
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.peers, key = { it.deviceId }) { peer ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(peer.name, Modifier.weight(1f))
                        TextButton(onClick = { confirm = peer }) { Text("Unpair") }
                    }
                }
            }
            Button(onClick = { pairing.restart(); showPairing = true }) { Text("Pair a PC") }
        }
    }

    confirm?.let { peer ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Unpair ${peer.name}?") },
            confirmButton = { TextButton(onClick = { vm.unpair(peer.deviceId); confirm = null }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PairingFlow(vm: PairingViewModel, state: PairingUi, onDone: () -> Unit) {
    var code by remember { mutableStateOf("") }
    when (state) {
        is PairingUi.Entering -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Code from your PC") },
                isError = state.error != null,
                supportingText = state.error?.let { { Text(it) } },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.submit(code) }) { Text("Pair") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        }
        is PairingUi.Waiting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("Waiting for ${state.pcName} to accept…")
        }
        is PairingUi.Paired -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paired with ${state.pcName}")
            Button(onClick = onDone) { Text("Done") }
        }
        is PairingUi.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = { code = ""; vm.restart() }) { Text("Try again") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }
}
