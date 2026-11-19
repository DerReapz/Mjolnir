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
import androidx.compose.runtime.DisposableEffect
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
import app.mjolnir.desktop.scanFiles
import app.mjolnir.desktop.uploadUnitKey
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.topFolder
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.launch

/** Files shown together: a top folder (all its files) or one loose file. */
data class FileGroup(val name: String, val files: List<LocalUpload>) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

/** Groups by the folder each file was added from (absolute path), so two "Photos" folders stay separate. */
fun fileGroups(files: List<LocalUpload>): List<FileGroup> =
    files.groupBy(::uploadUnitKey).values.map { list ->
        FileGroup(topFolder(list.first().relativePath) ?: list.first().relativePath, list)
    }

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SendWindow(c: AppController, icon: Painter, handoff: ReceiveChannel<List<File>>, onPair: () -> Unit, onClose: () -> Unit) {
    val peers by c.peers.collectAsState()
    val config by c.config.collectAsState()
    var state by remember { mutableStateOf(SendState().withTargets(peers, config.lastPhoneId)) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val open = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { open.set(false) } }
    // Walking folders and probing types reads the disk: never on the UI thread.
    fun addFiles(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch {
            val scan = withContext(Dispatchers.IO) { scanFiles(files) }
            state = state.withScanned(scan)
        }
    }

    LaunchedEffect(Unit) { c.refreshPeers() }
    LaunchedEffect(peers) { state = state.withTargets(peers, config.lastPhoneId) }

    fun applySendResult(r: app.mjolnir.desktop.SendResult, tabs: List<app.mjolnir.protocol.Tab>, files: List<LocalUpload>) {
        val sentUrls = r.sentTabs.map { it.url }.toSet()
        var next = state
        if (r.filesQueued) next = next.copy(files = next.files - files.toSet())
        next = if (sentUrls.isNotEmpty() && sentUrls.size == tabs.size) next.withText("") else next.copy(selected = next.selected - sentUrls)
        state = next.copy(notice = r.message)
        sending = false
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = when (val data = event.dragData()) {
                is DragData.FilesList -> {
                    val files = data.readFiles().mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
                    addFiles(files); true
                }
                is DragData.Text -> {
                    state = state.withText(listOf(state.text, data.readText()).filter { it.isNotBlank() }.joinToString("\n")); true
                }
                else -> false
            }
        }
    }

    Window(onCloseRequest = onClose, state = rememberWindowState(size = DpSize(560.dp, 640.dp)), title = "Send to device", icon = icon) {
        val awtWindow = window
        // Explorer's *Send to*: add the paths (scanned off the UI thread), aim at the last phone, and come to the front.
        LaunchedEffect(Unit) {
            for (files in handoff) {
                c.config.value.lastPhoneId?.let { state = state.selectTarget(it) }
                addFiles(files)
                bringToFront(awtWindow)
            }
        }
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
                        addFiles(chosen)
                    }) { Text("Add files…") }
                    OutlinedButton(onClick = {
                        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                        if (chooser.showOpenDialog(awtWindow) == JFileChooser.APPROVE_OPTION) {
                            addFiles(listOf(chooser.selectedFile))
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
                    TargetPicker(state, onPick = { state = state.selectTarget(it) }, onPair = onPair)
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = state.canSend && !sending,
                        onClick = {
                            val target = state.target ?: return@Button
                            val tabs = state.tabs(); val files = state.files
                            sending = true
                            // The send runs in the controller's scope, so closing this window cannot cancel it;
                            // if the window is gone by then, the result becomes a tray notification.
                            c.startSend(target, tabs, files) { r ->
                                if (!open.get()) return@startSend false
                                SwingUtilities.invokeLater { applySendResult(r, tabs, files) }
                                true
                            }
                        },
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun TargetPicker(state: SendState, onPick: (String) -> Unit, onPair: () -> Unit) {
    if (state.targets.isEmpty()) {
        TextButton(onClick = onPair) { Text("Pair a device…") }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text((state.target?.name ?: "") + "  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (peer in state.targets) {
                val label = if (peer.kind == DeviceKind.PHONE) "Phone" else "PC"
                DropdownMenuItem(text = { Text("${peer.name} · $label") }, onClick = { onPick(peer.deviceId); open = false })
            }
        }
    }
    Spacer(Modifier.width(8.dp))
}

/** Restores [w] if minimized and raises it above other windows (the alwaysOnTop toggle gets past focus rules). */
private fun bringToFront(w: Frame) {
    if (w.extendedState and Frame.ICONIFIED != 0) w.extendedState = w.extendedState and Frame.ICONIFIED.inv()
    w.isAlwaysOnTop = true
    w.toFront()
    w.requestFocus()
    w.isAlwaysOnTop = false
}
