package app.mjolnir.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.mjolnir.client.Messages
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.desktop.AppController
import app.mjolnir.desktop.couldntGetCode
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.PairingCode
import kotlinx.coroutines.delay

/** `m:ss` for a remaining time in milliseconds (rounded up to whole seconds). */
fun formatRemaining(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun PairWindow(c: AppController, icon: Painter, onClose: () -> Unit) {
    var generation by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }
    var deadline by remember { mutableLongStateOf(0L) }
    var remaining by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairedWith by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(generation) {
        code = null; error = null
        val api = c.api()
        val self = c.config.value.deviceName
        if (api == null) { error = couldntGetCode(Messages.failureReason(RelayErrorKind.NETWORK, self)); return@LaunchedEffect }
        try {
            val r = api.createPairingCode()
            // Count down locally from the moment the code arrived, so a skewed PC clock cannot shorten it.
            deadline = System.currentTimeMillis() + Limits.PAIRING_TTL_MS
            remaining = Limits.PAIRING_TTL_MS
            code = r.code
        } catch (e: RelayException) {
            error = couldntGetCode(Messages.failureReason(e.kind, self))
        }
    }
    LaunchedEffect(code) {
        while (code != null) {
            remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(250)
        }
    }
    LaunchedEffect(Unit) {
        c.pairedWith.collect {
            pairedWith = it
            delay(1500)
            onClose()
        }
    }

    Window(onCloseRequest = onClose, state = rememberWindowState(size = DpSize(380.dp, 300.dp)), title = "Pair a phone", icon = icon, resizable = false) {
        MjolnirTheme {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                val paired = pairedWith
                val current = code
                when {
                    paired != null -> Text("Paired with $paired", style = MaterialTheme.typography.titleLarge)
                    current != null && remaining > 0 -> {
                        Text(PairingCode.display(current), fontSize = 48.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("Expires in ${formatRemaining(remaining)}")
                    }
                    current != null || error != null -> {
                        error?.let { Text(it) }
                        Button(onClick = { generation++ }) { Text("Get a new code") }
                    }
                    else -> Text("…")
                }
            }
        }
    }
}
