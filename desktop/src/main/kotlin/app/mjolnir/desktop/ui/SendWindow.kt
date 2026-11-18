package app.mjolnir.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Messages
import app.mjolnir.desktop.AppController
import app.mjolnir.desktop.SendState
import app.mjolnir.protocol.topFolder
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import kotlinx.coroutines.launch

/** Files shown together: a top folder (all its files) or one loose file. */
data class FileGroup(val name: String, val files: List<LocalUpload>) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

fun fileGroups(files: List<LocalUpload>): List<FileGroup> =
    files.groupBy { topFolder(it.relativePath)?.let { top -> "dir:$top" } ?: "file:${it.path}" }
        .map { (key, list) -> FileGroup(if (key.startsWith("dir:")) key.removePrefix("dir:") else list.single().relativePath, list) }

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SendWindow(c: AppController, icon: Painter, onPair: () -> Unit, onClose: () -> Unit) {
    val peers by c.peers.collectAsState()
    val config by c.config.collectAsState()
    var state by remember { mutableStateOf(SendState().withPhones(peers, config.lastPhoneId)) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { c.refreshPeers() }
    LaunchedEffect(peers) { state = state.withPhones(peers, config.lastPhoneId) }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = when (val data = event.dragData()) {
                is DragData.FilesList -> {
                    val files = data.readFiles().mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
                    state = state.withFiles(files); true
                }
                is DragData.Text -> {
                    state = state.withText(listOf(state.text, data.readText()).filter { it.isNotBlank() }.joinToString("\n")); true
                }
                else -> false
            }
        }
    }

    Window(onCloseRequest = onClose, state = rememberWindowState(size = DpSize(560.dp, 640.dp)), title = "Send to phone", icon = icon) {
        val awtWindow = window
        MjolnirTheme {
            Column(
                Modifier.fillMaxSize().padding(16.dp).dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = { state = state.withText(it) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 6,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val dialog = FileDialog(awtWindow as Frame, "Add files…", FileDialog.LOAD).apply { isMultipleMode = true }
                        dialog.isVisible = true
                        val chosen = dialog.files.toList()
                        if (chosen.isNotEmpty()) state = state.withFiles(chosen)
                    }) { Text("Add files…") }
                    OutlinedButton(onClick = {
                        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                        if (chooser.showOpenDialog(awtWindow) == JFileChooser.APPROVE_OPTION) {
                            state = state.withFiles(listOf(chooser.selectedFile))
                        }
                    }) { Text("Add folder…") }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(state.links, key = { "link:$it" }) { link ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = link in state.selected, onCheckedChange = { state = state.toggle(link) })
                            Text(link, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    items(fileGroups(state.files), key = { "file:" + it.files.first().path }) { group ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${Messages.items(0, group.files.size)} · ${Messages.size(group.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = {
                                state = group.files.fold(state) { s, f -> s.removeFile(f.path) }.copy(notice = null)
                            }) { Text("Remove") }
                        }
                    }
                }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhonePicker(state, onPick = { state = state.selectTarget(it) }, onPair = onPair)
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = state.canSend && !sending,
                        onClick = {
                            val target = state.target ?: return@Button
                            val tabs = state.tabs(); val files = state.files
                            sending = true
                            scope.launch {
                                try {
                                    val r = c.send(target, tabs, files)
                                    val sentUrls = r.sentTabs.map { it.url }.toSet()
                                    var next = state
                                    if (r.filesQueued) next = next.copy(files = emptyList())
                                    next = if (sentUrls.isNotEmpty() && sentUrls.size == tabs.size) {
                                        next.withText("")
                                    } else {
                                        next.copy(selected = next.selected - sentUrls)
                                    }
                                    state = next.copy(notice = r.message)
                                } finally {
                                    sending = false
                                }
                            }
                        },
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun PhonePicker(state: SendState, onPick: (String) -> Unit, onPair: () -> Unit) {
    if (state.phones.isEmpty()) {
        TextButton(onClick = onPair) { Text("Pair a phone…") }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text((state.target?.name ?: "") + "  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (phone in state.phones) {
                DropdownMenuItem(text = { Text(phone.name) }, onClick = { onPick(phone.deviceId); open = false })
            }
        }
    }
    Spacer(Modifier.width(8.dp))
}
