package app.mjolnir.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
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

    Window(onCloseRequest = onClose, state = rememberWindowState(size = DpSize(760.dp, 560.dp)), title = "Send to device", icon = icon) {
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
            SendContent(
                state = state,
                lastTargetId = config.lastPhoneId,
                sending = sending,
                modifier = Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget),
                onState = { state = it },
                onAddFiles = {
                    val dialog = FileDialog(awtWindow as Frame, "Add files…", FileDialog.LOAD).apply { isMultipleMode = true }
                    dialog.isVisible = true
                    val chosen = dialog.files.toList()
                    addFiles(chosen)
                },
                onAddFolder = {
                    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                    if (chooser.showOpenDialog(awtWindow) == JFileChooser.APPROVE_OPTION) {
                        addFiles(listOf(chooser.selectedFile))
                    }
                },
                onPair = onPair,
                onSend = {
                    val target = state.target ?: return@SendContent
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
            )
        }
    }
}

/** The Send window's content (stateless, so it can be rendered headless). Layout per the Forge mockup. */
@Composable
internal fun SendContent(
    state: SendState,
    lastTargetId: String?,
    sending: Boolean,
    modifier: Modifier = Modifier,
    onState: (SendState) -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onPair: () -> Unit,
    onSend: () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        ForgeHeader("Send to device")
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Paste or drop links, files and folders", style = MaterialTheme.typography.bodySmall.copy(color = Forge.muted))
                    BasicTextField(
                        value = state.text,
                        onValueChange = { onState(state.withText(it)) },
                        modifier = Modifier.fillMaxWidth().dashedBorder(Forge.dashed, 10.dp).background(Forge.surface, Forge.inputShape).padding(10.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        cursorBrush = SolidColor(Forge.accent),
                        minLines = 3, maxLines = 6,
                    )
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.links, key = { "link:$it" }) { link ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 40.dp).forgeRow().padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Checkbox(checked = link in state.selected, onCheckedChange = { onState(state.toggle(link)) }, colors = forgeCheckboxColors())
                            ForgeBodyText(link, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    items(fileGroups(state.files), key = { "file:" + it.files.first().path }) { group ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 40.dp).forgeRow().padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                ForgeBodyText(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${Messages.items(0, group.files.size)} · ${Messages.size(group.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = Forge.muted),
                                )
                            }
                            ForgeTextButton("Remove", muted = true, onClick = {
                                onState(group.files.fold(state) { s, f -> s.removeFile(f.path) }.copy(notice = null))
                            })
                        }
                    }
                }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeSecondaryButton("Add files…", onClick = onAddFiles)
                    ForgeSecondaryButton("Add folder…", onClick = onAddFolder)
                }
            }
            Column(
                Modifier.width(240.dp).fillMaxHeight().background(Forge.panel).drawBehind {
                    drawLine(Forge.border, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                }.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ForgeSectionLabel("Send to")
                if (state.targets.isEmpty()) {
                    ForgeTextButton("Pair a device…", onClick = onPair)
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.targets, key = { it.deviceId }) { peer ->
                        val selected = peer.deviceId == state.targetId
                        val kind = if (peer.kind == DeviceKind.PHONE) "Phone" else "PC"
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(Forge.inputShape).forgeCard(selected)
                                .clickable { onState(state.selectTarget(peer.deviceId)) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(peer.name, style = TextStyle(fontFamily = ForgeFonts.inter600, fontSize = 14.sp, color = Forge.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (peer.deviceId == lastTargetId) "$kind · last used" else kind,
                                style = MaterialTheme.typography.bodySmall.copy(color = Forge.muted),
                            )
                        }
                    }
                }
                ForgePrimaryButton("Send", onClick = onSend, enabled = state.canSend && !sending, big = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
    drawRoundRect(color, cornerRadius = CornerRadius(radius.toPx()), style = stroke)
}

/** Restores [w] if minimized and raises it above other windows (the alwaysOnTop toggle gets past focus rules). */
private fun bringToFront(w: Frame) {
    if (w.extendedState and Frame.ICONIFIED != 0) w.extendedState = w.extendedState and Frame.ICONIFIED.inv()
    w.isAlwaysOnTop = true
    w.toFront()
    w.requestFocus()
    w.isAlwaysOnTop = false
}
