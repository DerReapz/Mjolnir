package app.mjolnir.desktop.ui

import app.mjolnir.client.RelayUrls
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
import app.mjolnir.desktop.MainTab
import app.mjolnir.protocol.RelayEvent
import java.io.File
import javax.imageio.ImageIO
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
    val nicknames by c.nicknames.changes.collectAsState()
    val tooltip by c.tooltip.collectAsState()
    val pairRequest by c.pairRequest.collectAsState()
    val icon = remember { loadIcon() }

    // First run without a relay address: open straight on Settings.
    val needsRelay = remember { RelayUrls.normalize(config.relayUrl) == null }
    var showMain by remember { mutableStateOf(needsRelay) }
    var mainTab by remember { mutableStateOf(if (needsRelay) MainTab.SETTINGS else MainTab.SEND) }

    LaunchedEffect(Unit) {
        c.notifications.collect { trayState.sendNotification(Notification("Mjolnir", it)) }
    }
    LaunchedEffect(Unit) {
        c.openRequests.collect { tab -> showMain = true; mainTab = if (tab == MainTab.GUEST) MainTab.SEND else tab }
    }
    // Paths from Explorer's *Send to* (this launch or a later one): the controller queues them and asks for the window.
    LaunchedEffect(Unit) {
        incoming.collect { paths ->
            if (paths.isEmpty()) {
                c.notify(ALREADY_RUNNING) // launched again with nothing to send
            } else {
                c.handOff(paths.map(::File))
            }
        }
    }

    Tray(icon = icon, state = trayState, tooltip = tooltip, onAction = { c.openWindow(mainTab) }, menu = {
        Item("Open Mjolnir", onClick = { c.openWindow(mainTab) }) // the last tab shown
        Item("Send to device…", onClick = { c.openWindow(MainTab.SEND) })
        val lastPhoneId = config.lastPhoneId
        if (lastPhoneId != null) {
            val name = peers.find { it.deviceId == lastPhoneId }?.let { nicknames.nicknameOr(it.deviceId, it.name) } ?: "device"
            Item("Send clipboard to $name", onClick = { c.sendClipboard() })
        }
        Item("Open received files", onClick = { c.openReceivedFiles() })
        Separator()
        Item("Quit Mjolnir", onClick = onQuit)
    })

    if (showMain) {
        MainWindow(
            c, mainTab,
            onTab = { mainTab = it },
            onClose = { if (config.keepInTray) showMain = false else onQuit() },
            icon = icon,
        )
    }
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
                    ForgeSecondaryButton("Reject", onClick = { c.answerPairRequest(request, accept = false) })
                    Spacer(Modifier.width(12.dp))
                    ForgePrimaryButton("Accept", onClick = { c.answerPairRequest(request, accept = true) })
                }
            }
        }
    }
}
