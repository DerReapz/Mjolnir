package app.mjolnir.android.ui

import app.mjolnir.android.session.resetQuietly
import java.io.IOException
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.mjolnir.protocol.PairingCode
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.LocalActivity
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
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DevicesUi(val peers: List<Peer> = emptyList(), val notice: String? = null)

class DevicesViewModel(private val api: RelayApi, private val session: PhoneSession) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUi())
    val state: StateFlow<DevicesUi> = _state.asStateFlow()

    fun refresh() = run("Couldn't load devices") { _state.value = _state.value.copy(peers = api.peers(), notice = null) }

    fun unpair(deviceId: String) = run("Couldn't unpair") {
        api.unpair(deviceId)
        _state.value = _state.value.copy(peers = api.peers(), notice = null)
    }

    private fun run(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: RelayException) {
                if (e.kind == RelayErrorKind.UNAUTHORIZED) {
                    resetQuietly(session)
                    _state.value = DevicesUi(notice = Messages.RESET)
                } else {
                    _state.value = _state.value.copy(notice = "$failure: ${Messages.failureReason(e.kind, "your PC")}.")
                }
            } catch (e: IOException) { // the phone's own config couldn't be read or written
                _state.value = _state.value.copy(notice = "$failure.")
            }
        }
    }
}

@Composable
fun DevicesScreen(vm: DevicesViewModel, pairing: PairingViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val pairingState by pairing.state.collectAsState()
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Peer?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(pairingState) { if (pairingState is PairingUi.Paired) vm.refresh() }

    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showPairing) {
            PairingFlow(pairing, pairingState) { pairing.restart(); showPairing = false }
        } else {
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.peers, key = { it.deviceId }) { peer ->
                    ForgeCard {
                        Column(Modifier.weight(1f)) {
                            Text(peer.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                            Text(if (peer.kind == DeviceKind.PC) "PC" else "Phone", style = MaterialTheme.typography.bodySmall, color = Forge.Muted)
                        }
                        TextButton(onClick = { confirm = peer }) { Text("Unpair") }
                    }
                }
            }
            Button(onClick = { pairing.restart(); showPairing = true }, shape = ButtonShape) { Text("Pair a device") }
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

private enum class PairMode { CHOOSE, ENTER, SHOW }

/** `m:ss` for the time left on a code. */
internal fun formatRemaining(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun PairingFlow(vm: PairingViewModel, state: PairingUi, onDone: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(PairMode.CHOOSE) }
    var code by remember { mutableStateOf("") }
    when {
        state is PairingUi.Paired -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paired with ${state.name}", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onDone, shape = ButtonShape) { Text("Done") }
        }
        mode == PairMode.CHOOSE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { code = ""; vm.restart(); mode = PairMode.ENTER }, shape = ButtonShape) { Text("Enter a code") }
            Button(onClick = { mode = PairMode.SHOW }, shape = ButtonShape) { Text("Show a code") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
        mode == PairMode.SHOW -> ShowCode(vm, state, onBack = { vm.stopShowing(); mode = PairMode.CHOOSE })
        else -> when (state) {
            is PairingUi.Waiting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("Waiting for ${state.name} to accept…")
            }
            is PairingUi.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { code = ""; vm.restart() }, shape = ButtonShape) { Text("Try again") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val error = (state as? PairingUi.Entering)?.error
                OutlinedTextField(
                    shape = FieldShape,
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code from the other device") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.submit(code) }, shape = ButtonShape) { Text("Pair") }
                    TextButton(onClick = { vm.restart(); mode = PairMode.CHOOSE }) { Text("Back") }
                }
            }
        }
    }
}

/** The code-showing view. It listens for a request only while it is on screen. */
@Composable
private fun ShowCode(vm: PairingViewModel, state: PairingUi, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.ensureShowing() } // resumes the code after a rotation
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        // A rotation keeps the code and any pending request; leaving the view stops listening.
        onDispose { if (activity?.isChangingConfigurations != true) vm.stopShowing() }
    }
    var remaining by remember { mutableLongStateOf(0L) }
    val showing = state as? PairingUi.Showing
    LaunchedEffect(showing?.code) {
        val until = showing?.expiresAtEpochMs ?: return@LaunchedEffect
        while (true) {
            remaining = until - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(250)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            is PairingUi.Showing -> {
                Text(PairingCode.display(state.code), color = Forge.Accent, fontSize = 48.sp, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Expires in ${formatRemaining(remaining)}", color = Forge.Muted)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            is PairingUi.Expired -> {
                Text("The code expired.")
                Button(onClick = { vm.showCode() }, shape = ButtonShape) { Text("Get a new code") }
            }
            is PairingUi.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.showCode() }, shape = ButtonShape) { Text("Get a new code") }
            }
            else -> CircularProgressIndicator(Modifier.size(24.dp))
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
    (showing?.request)?.let { peer ->
        AlertDialog(
            onDismissRequest = {}, // answer with a button, so a stray tap can't dismiss it
            title = { Text("Pair with ${peer.name}?") },
            confirmButton = { TextButton(onClick = { vm.answer(true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { vm.answer(false) }) { Text("Reject") } },
        )
    }
}
