package app.mjolnir.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.mjolnir.desktop.AppController
import app.mjolnir.protocol.DeviceKind

@Composable
fun DevicesWindow(c: AppController, icon: Painter, onPair: () -> Unit, onClose: () -> Unit) {
    val peers by c.peers.collectAsState()
    LaunchedEffect(Unit) { c.refreshPeers() }

    Window(onCloseRequest = onClose, state = rememberWindowState(size = DpSize(420.dp, 360.dp)), title = "Paired devices", icon = icon) {
        MjolnirTheme {
            Column(Modifier.fillMaxSize()) {
                ForgeHeader("Paired devices")
                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(peers, key = { it.deviceId }) { peer ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).forgeCard(selected = false).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(peer.name, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ForgeFonts.inter600))
                                    Text(if (peer.kind == DeviceKind.PHONE) "Phone" else "PC", style = MaterialTheme.typography.bodySmall.copy(color = Forge.muted))
                                }
                                ForgeSecondaryButton("Unpair", onClick = { c.startUnpair(peer) })
                            }
                        }
                    }
                    ForgePrimaryButton("Pair a device…", onClick = onPair)
                }
            }
        }
    }
}
