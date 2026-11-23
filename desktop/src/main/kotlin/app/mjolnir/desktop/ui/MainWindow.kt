package app.mjolnir.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.mjolnir.client.GuestRoomUi
import app.mjolnir.client.Messages
import app.mjolnir.client.UpdateState
import app.mjolnir.desktop.AppController
import app.mjolnir.desktop.ItemPreview
import app.mjolnir.desktop.MainTab
import app.mjolnir.desktop.SendState
import app.mjolnir.desktop.browseWithSystem
import app.mjolnir.desktop.previewOf
import app.mjolnir.desktop.scanFiles
import app.mjolnir.desktop.uploadUnitKey
import app.mjolnir.protocol.DeviceKind
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val STATUS_CONNECTED = Color(0xFF4ADE80)

/** The one main window: sidebar plus the selected tab. Closing it is up to [onClose] (hide to tray, or quit). */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun MainWindow(c: AppController, tab: MainTab, onTab: (MainTab) -> Unit, onClose: () -> Unit, icon: Painter? = null) {
    val send by c.send.collectAsState() // the queue is owned by the controller and outlives this window
    val nicknames by c.nicknames.changes.collectAsState()
    val relayStatus by c.relayStatus.collectAsState()
    val chatUnread by c.chats.unread.collectAsState()
    val guestUi by c.guestRoom.state.collectAsState()
    val exchanged by c.guestRoom.exchanged.collectAsState()
    val guestLeft = rememberRoomCountdown(guestUi)
    val update by c.updateState.collectAsState()
    val relayTooOld by c.relayTooOld.collectAsState()
    var pairOnOpen by remember { mutableStateOf(false) } // the Send tab's *Pair a device* opens Devices with pairing started
    val scope = rememberCoroutineScope()
    val previews = remember { mutableStateMapOf<String, ItemPreview>() }

    DisposableEffect(c) {
        c.mainWindowVisible = true
        onDispose { c.mainWindowVisible = false }
    }
    SideEffect { c.mainWindowTab = tab } // a send result is shown in the window only on the Send tab
    LaunchedEffect(Unit) { c.refreshPeers() }
    // Folders and probing types read the disk: previews are built off the UI thread and kept per row.
    LaunchedEffect(send.files) {
        evictStalePreviews(previews, send)
        for (key in send.files.map(::uploadUnitKey).distinct()) {
            if (key !in previews) previews[key] = withContext(Dispatchers.IO) { previewOf(File(key)) }
        }
    }

    // Walking folders and probing types reads the disk: never on the UI thread.
    fun addFiles(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch {
            val scan = withContext(Dispatchers.IO) { scanFiles(files) }
            c.updateSend { it.withScanned(scan) }
        }
    }
    fun addText(text: String) {
        if (text.isBlank()) return
        c.updateSend { it.withText(listOf(it.text, text).filter { t -> t.isNotBlank() }.joinToString("\n")) }
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = when (val data = event.dragData()) {
                is DragData.FilesList -> {
                    onTab(MainTab.SEND) // the queue is on the Send tab: show where the drop went
                    addFiles(data.readFiles().mapNotNull { runCatching { File(URI(it)) }.getOrNull() }); true
                }
                is DragData.Text -> { onTab(MainTab.SEND); addText(data.readText()); true }
                else -> false
            }
        }
    }

    Window(
        onCloseRequest = onClose, state = rememberWindowState(size = DpSize(1040.dp, 680.dp)),
        title = "Mjolnir", icon = icon,
        onPreviewKeyEvent = { e ->
            // Ctrl+V anywhere on the Send tab: files from Explorer, else text (links).
            if (tab == MainTab.SEND && e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.V) {
                val (files, text) = readClipboard()
                if (files.isNotEmpty()) addFiles(files) else if (text != null) addText(text)
                files.isNotEmpty() || text != null
            } else if (tab == MainTab.CHATS && e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.V) {
                // Files pasted into a chat you're in are sent there, unless a dialog or the viewer is open; text pastes into
                // the composer as usual.
                val chat = c.chatPasteTarget()
                val files = if (chat != null) readClipboard().first else emptyList()
                if (chat != null && files.isNotEmpty()) c.sendChatFiles(chat, files)
                files.isNotEmpty()
            } else false
        },
    ) {
        val awtWindow = window
        LaunchedEffect(awtWindow) { awtWindow.minimumSize = Dimension(900, 600) }
        DisposableEffect(awtWindow) { // a minimised window is not one the user can see
            val listener = java.awt.event.WindowStateListener { c.setWindowMinimized(it.newState and Frame.ICONIFIED != 0) }
            awtWindow.addWindowStateListener(listener)
            onDispose { awtWindow.removeWindowStateListener(listener) }
        }
        LaunchedEffect(Unit) { c.openRequests.collect { bringToFront(awtWindow) } } // Explorer's *Send to*, tray: come to the front
        MjolnirTheme {
            Column(Modifier.fillMaxSize()) {
                UpdateBanner(update, relayTooOld, onInstall = { c.installUpdate() }) // runs in the controller's scope
                Row(Modifier.fillMaxWidth().weight(1f).dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)) {
                    Sidebar(selected = tab, relayStatus = relayStatus.label, guestBadge = guestLeft, onSelect = onTab, chatsBadge = chatUnread.values.sum())
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (tab) {
                            MainTab.SEND -> SendTabContent(
                                state = send,
                                nicknameOf = { peer -> if (peer.kind == DeviceKind.GUEST) "Guest" else nicknames.nicknameOr(peer.deviceId, peer.name) }, // reads the flow so renames recompose
                                previews = previews,
                                sending = send.sending,
                                onUpdate = c::updateSend,
                                onAddFiles = {
                                    val dialog = FileDialog(awtWindow as Frame, "Add files…", FileDialog.LOAD).apply { isMultipleMode = true }
                                    dialog.isVisible = true
                                    addFiles(dialog.files.toList())
                                },
                                onAddFolder = {
                                    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                                    if (chooser.showOpenDialog(awtWindow) == JFileChooser.APPROVE_OPTION) addFiles(listOf(chooser.selectedFile))
                                },
                                onPair = { pairOnOpen = true; onTab(MainTab.DEVICES) },
                                onSend = { c.sendSelected() }, // runs in the controller's scope, so closing this window cannot cancel it
                                onOpen = ::openPreview,
                                onShowInFolder = ::showPreviewInFolder,
                                guestLeft = guestLeft,
                            )
                            MainTab.CHATS -> ChatsTab(c, parent = awtWindow)
                            MainTab.RECEIVED -> ReceivedTab(c)
                            MainTab.DEVICES -> DevicesTab(c, openPairing = pairOnOpen, onPairingOpened = { pairOnOpen = false })
                            MainTab.SETTINGS -> SettingsTab(c, parent = awtWindow)
                            MainTab.GUEST -> GuestTabContent(
                                ui = guestUi, exchanged = exchanged,
                                onOpen = c.guestRoom::open,
                                onEnd = c.guestRoom::end,
                                onCopy = { (guestUi as? GuestRoomUi.Open)?.link?.takeIf { it.isNotBlank() }?.let(::copyToClipboard) },
                                onAddFiles = {
                                    val dialog = FileDialog(awtWindow as Frame, "Add files…", FileDialog.LOAD).apply { isMultipleMode = true }
                                    dialog.isVisible = true
                                    c.sendToGuest(dialog.files.toList())
                                },
                                onAddFolder = {
                                    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                                    if (chooser.showOpenDialog(awtWindow) == JFileChooser.APPROVE_OPTION) c.sendToGuest(listOf(chooser.selectedFile))
                                },
                                onDrop = c::sendToGuest,
                                onOpenFolder = { path -> File(path).let { f -> if (f.isDirectory) openFile(f) else revealInFolder(f) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The update banner's text and whether it offers *Install*; null when there is nothing to show. A relay older than this
 * app takes the slot ([Messages.RELAY_TOO_OLD]) unless the user already started an update (downloading, failed, installing).
 */
internal fun updateBanner(state: UpdateState, relayTooOld: Boolean): Pair<String, Boolean>? = when (state) {
    is UpdateState.Downloading -> Messages.downloadingUpdate(state.percent) to false
    is UpdateState.Failed -> Messages.updateFailed(state.reason) to true
    is UpdateState.Ready -> Messages.INSTALLING to false
    else -> when {
        relayTooOld -> Messages.RELAY_TOO_OLD to false
        state is UpdateState.Available -> Messages.updateAvailable(state.version) to true
        else -> null
    }
}

/** The strip at the top of the main window: an app update (with *Install*) or a relay too old for this app. */
@Composable
fun UpdateBanner(state: UpdateState, relayTooOld: Boolean, onInstall: () -> Unit) {
    val (text, install) = updateBanner(state, relayTooOld) ?: return
    Row(
        Modifier.fillMaxWidth().background(Forge.surface)
            .drawBehind { drawLine(Forge.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.text), modifier = Modifier.weight(1f))
        if (install) ForgePrimaryButton(Messages.INSTALL, onClick = onInstall)
    }
}

/** Drops previews whose row has left the queue, so the map does not hold every file ever added. */
internal fun evictStalePreviews(previews: MutableMap<String, ItemPreview>, send: SendState) {
    val live = send.links.toSet() + send.files.map(::uploadUnitKey)
    previews.keys.retainAll(live)
}

private class NavItem(val tab: MainTab, val label: String, val icon: List<String>)

private val NAV = listOf(
    NavItem(MainTab.SEND, "Send", listOf("M22 2L11 13", "M22 2l-7 20-4-9-9-4z")),
    NavItem(MainTab.CHATS, "Chats", listOf("M21 12a8 8 0 0 1-11.5 7.2L4 21l1.8-5.5A8 8 0 1 1 21 12z")),
    NavItem(MainTab.GUEST, "Guest room", listOf("M3 21V8l9-5 9 5v13", "M9 21v-7h6v7")),
    NavItem(MainTab.RECEIVED, "Received", listOf("M12 3v12", "M7 10l5 5 5-5", "M4 21h16")),
    NavItem(MainTab.DEVICES, "Devices", listOf("M3 4h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M18 8h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", "M6 18h6")),
    NavItem(MainTab.SETTINGS, "Settings", listOf(
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
    )),
)

internal val ICON_FOLDER = listOf("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")

/** A 24-unit stroke icon (the mockup's SVG paths), drawn at [size]. */
@Composable
internal fun ForgeIcon(paths: List<String>, tint: Color, size: Dp) {
    val parsed = remember(paths) { paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(Modifier.size(size)) {
        withTransform({ scale(this@Canvas.size.width / 24f, this@Canvas.size.height / 24f, Offset.Zero) }) {
            parsed.forEach { drawPath(it, tint, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

/** 208 px sidebar: wordmark, nav items, relay status. The Guest room item carries [guestBadge] (the room's countdown) while a room is open; Chats carries [chatsBadge], the unread total. */
@Composable
fun Sidebar(selected: MainTab, relayStatus: String, guestBadge: String?, onSelect: (MainTab) -> Unit, chatsBadge: Int = 0) {
    Column(
        Modifier.width(208.dp).fillMaxHeight().background(Forge.panel)
            .drawBehind { drawLine(Forge.border, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 12.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(forgeHammer(), contentDescription = null, modifier = Modifier.size(26.dp))
            Text("MJOLNIR", style = TextStyle(fontFamily = ForgeFonts.grotesk700, fontSize = 18.sp, letterSpacing = 0.5.sp, color = Forge.text))
        }
        for (item in NAV) {
            if (item.tab == MainTab.SETTINGS) Spacer(Modifier.weight(1f))
            val active = item.tab == selected
            val tint = if (active) Forge.accent else Forge.muted
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(Forge.buttonShape)
                    .background(if (active) Forge.surface else Color.Transparent)
                    .clickable { onSelect(item.tab) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ForgeIcon(item.icon, tint, 18.dp)
                Text(item.label, style = TextStyle(fontFamily = if (active) ForgeFonts.inter600 else ForgeFonts.inter400, fontSize = 14.sp, color = tint), modifier = Modifier.weight(1f))
                if (item.tab == MainTab.GUEST && guestBadge != null) ForgeBadge(guestBadge)
                if (item.tab == MainTab.CHATS && chatsBadge > 0) ForgeBadge(chatsBadge.toString()) // all chats' unread messages
            }
        }
        Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dot = when (relayStatus) { "Relay connected" -> STATUS_CONNECTED; "Relay not set" -> Forge.error; else -> Forge.accent }
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(relayStatus, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = Forge.muted))
        }
    }
}

internal fun copyToClipboard(text: String) {
    try { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } catch (_: Exception) {}
}

private fun readClipboard(): Pair<List<File>, String?> = try {
    val cb = Toolkit.getDefaultToolkit().systemClipboard
    when {
        cb.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) ->
            @Suppress("UNCHECKED_CAST") ((cb.getData(DataFlavor.javaFileListFlavor) as List<File>) to null)
        cb.isDataFlavorAvailable(DataFlavor.stringFlavor) -> emptyList<File>() to (cb.getData(DataFlavor.stringFlavor) as? String)
        else -> emptyList<File>() to null
    }
} catch (_: Exception) {
    emptyList<File>() to null
}

private fun openPreview(p: ItemPreview) {
    when (p) {
        is ItemPreview.Photo -> openFile(File(p.path))
        is ItemPreview.OtherFile -> openFile(File(p.path))
        is ItemPreview.Folder -> openFile(File(p.path))
        is ItemPreview.Link -> try { browseWithSystem(URI(p.url)) } catch (_: Exception) {}
        is ItemPreview.Missing -> {}
    }
}

private fun showPreviewInFolder(p: ItemPreview) {
    when (p) {
        is ItemPreview.Photo -> revealInFolder(File(p.path))
        is ItemPreview.OtherFile -> revealInFolder(File(p.path))
        is ItemPreview.Folder -> revealInFolder(File(p.path))
        else -> {}
    }
}

/** Restores [w] if minimized and raises it above other windows (the alwaysOnTop toggle gets past focus rules). */
private fun bringToFront(w: Frame) {
    if (w.extendedState and Frame.ICONIFIED != 0) w.extendedState = w.extendedState and Frame.ICONIFIED.inv()
    w.isAlwaysOnTop = true
    w.toFront()
    w.requestFocus()
    w.isAlwaysOnTop = false
}
