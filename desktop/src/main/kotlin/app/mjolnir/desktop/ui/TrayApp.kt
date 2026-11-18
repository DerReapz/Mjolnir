package app.mjolnir.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberTrayState
import app.mjolnir.desktop.ALREADY_RUNNING
import app.mjolnir.desktop.AppController
import app.mjolnir.protocol.RelayEvent
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

fun loadIcon(): Painter {
    val image = AppController::class.java.getResourceAsStream("/tray.png")!!.use { ImageIO.read(it) }
    return BitmapPainter(image.toComposeImageBitmap())
}

@Composable
fun ApplicationScope.TrayApp(c: AppController, incoming: Flow<List<String>> = emptyFlow(), onQuit: () -> Unit) {
    val trayState = rememberTrayState()
    val config by c.config.collectAsState()
    val peers by c.peers.collectAsState()
    val tooltip by c.tooltip.collectAsState()
    val pairRequest by c.pairRequest.collectAsState()
    val icon = remember { loadIcon() }

    var showSend by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(config.relayUrl.isBlank()) }

    LaunchedEffect(Unit) {
        c.notifications.collect { trayState.sendNotification(Notification("Mjolnir", it)) }
    }
    // Paths from Explorer's *Send to* (this launch or a later one), waiting for the Send window to take them.
    val handoff = remember { Channel<List<File>>(Channel.UNLIMITED) }
    LaunchedEffect(Unit) {
        incoming.collect { paths ->
            if (paths.isEmpty()) {
                c.notify(ALREADY_RUNNING) // launched again with nothing to send
            } else {
                handoff.trySend(paths.map(::File))
                showSend = true
            }
        }
    }

    Tray(icon = icon, state = trayState, tooltip = tooltip, onAction = { showSend = true }, menu = {
        Item("Send to phone…", onClick = { showSend = true })
        val lastPhoneId = config.lastPhoneId
        if (lastPhoneId != null) {
            val name = peers.find { it.deviceId == lastPhoneId }?.name ?: "phone"
            Item("Send clipboard to $name", onClick = { c.sendClipboard() })
        }
        Item("Open received files", onClick = { c.openReceivedFiles() })
        Separator()
        Item("Pair a phone…", onClick = { showPair = true })
        Item("Paired devices…", onClick = { showDevices = true })
        Item("Settings…", onClick = { showSettings = true })
        Separator()
        Item("Quit Mjolnir", onClick = onQuit)
    })

    if (showSend) SendWindow(c, icon, handoff, onPair = { showPair = true }, onClose = { showSend = false })
    if (showPair) PairWindow(c, icon, onClose = { showPair = false })
    if (showDevices) DevicesWindow(c, icon, onPair = { showPair = true }, onClose = { showDevices = false })
    if (showSettings) SettingsWindow(c, icon, onClose = { showSettings = false })
    pairRequest?.let { PairRequestDialog(c, it, icon) }
}

/** `Pair with {name}?` — closing the dialog counts as Reject. */
@Composable
private fun PairRequestDialog(c: AppController, request: RelayEvent.PairRequested, icon: Painter) {
    // Answers run in the controller's scope: this dialog leaves the composition as soon as the request clears.
    DialogWindow(
        onCloseRequest = { c.answerPairRequest(request, accept = false) },
        state = rememberDialogState(size = DpSize(360.dp, 170.dp)),
        title = "Mjolnir",
        icon = icon,
        alwaysOnTop = true,
    ) {
        MjolnirTheme {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Pair with ${request.from.name}?", style = MaterialTheme.typography.titleMedium)
                Row {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { c.answerPairRequest(request, accept = false) }) { Text("Reject") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { c.answerPairRequest(request, accept = true) }) { Text("Accept") }
                }
            }
        }
    }
}

@Composable
fun MjolnirTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}
