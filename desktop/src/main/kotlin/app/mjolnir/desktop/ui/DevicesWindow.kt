package app.mjolnir.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(Modifier.weight(1f)) {
                    items(peers, key = { it.deviceId }) { peer ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.name, style = MaterialTheme.typography.bodyLarge)
                                Text(if (peer.kind == DeviceKind.PHONE) "Phone" else "PC", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { c.startUnpair(peer) }) { Text("Unpair") }
                        }
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = onPair) { Text("Pair a device…") }
            }
        }
    }
}
