package app.mjolnir.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.mjolnir.client.RelayUrls
import app.mjolnir.desktop.AppController
import app.mjolnir.desktop.saveDirPath
import app.mjolnir.protocol.Limits
import javax.swing.JFileChooser

const val USE_HTTPS = "Use an https:// address"
const val RELAY_CHANGE_WARNING = "Changing the relay unpairs all devices."

/** What the relay field should show under it, or null. */
fun relayFieldMessage(input: String, current: String, attempted: Boolean): String? {
    val normalized = RelayUrls.normalize(input)
    return when {
        normalized == null -> if (attempted || input.isNotBlank()) USE_HTTPS else null
        current.isNotBlank() && normalized != current -> RELAY_CHANGE_WARNING
        else -> null
    }
}

fun isValidDeviceName(name: String): Boolean = name.trim().length in 1..Limits.MAX_DEVICE_NAME_LENGTH

@Composable
fun SettingsWindow(c: AppController, icon: Painter, onClose: () -> Unit) {
    val config by c.config.collectAsState()
    var relay by remember { mutableStateOf(config.relayUrl) }
    var relayAttempted by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(config.deviceName) }
    var nameError by remember { mutableStateOf<String?>(null) }

    Window(onCloseRequest = onClose, state = rememberWindowState(size = DpSize(520.dp, 520.dp)), title = "Settings", icon = icon) {
        val awtWindow = window
        MjolnirTheme {
          Column(Modifier.fillMaxSize()) {
            ForgeHeader("Settings")
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Relay", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = relay, onValueChange = { relay = it; relayAttempted = false }, singleLine = true,
                        modifier = Modifier.weight(1f), placeholder = { Text("https://") }, shape = Forge.inputShape, colors = forgeFieldColors())
                    ForgeSecondaryButton("Save", onClick = {
                        relayAttempted = true
                        val url = RelayUrls.normalize(relay) ?: return@ForgeSecondaryButton
                        relay = url
                        c.changeRelay(url) // runs in the controller's scope: closing Settings cannot interrupt it
                    })
                }
                relayFieldMessage(relay, config.relayUrl, relayAttempted)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                Text("Device name", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it; nameError = null }, singleLine = true,
                        modifier = Modifier.weight(1f), isError = !isValidDeviceName(name), shape = Forge.inputShape, colors = forgeFieldColors())
                    ForgeSecondaryButton(
                        "Save", enabled = isValidDeviceName(name) && name.trim() != config.deviceName,
                        onClick = { c.startRename(name) { err -> nameError = err; if (err == null) name = name.trim() } },
                    )
                }
                nameError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                Text("Save received files to", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(config.saveDirPath().absolutePath, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ForgeSecondaryButton("Change…", onClick = {
                        val chooser = JFileChooser(config.saveDirPath()).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                        if (chooser.showOpenDialog(awtWindow) == JFileChooser.APPROVE_OPTION) c.setSaveDir(chooser.selectedFile)
                    })
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = config.launchAtLogin, onCheckedChange = { c.setLaunchAtLogin(it) }, colors = forgeCheckboxColors())
                    Text("Start Mjolnir when I sign in to Windows")
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = config.sendToMenu, onCheckedChange = { c.setSendToMenu(it) }, colors = forgeCheckboxColors())
                    Text("Show Mjolnir in Explorer's Send to menu")
                }
            }
          }
        }
    }
}
