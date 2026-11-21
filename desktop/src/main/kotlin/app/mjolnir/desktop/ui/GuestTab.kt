package app.mjolnir.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.Exchanged
import app.mjolnir.client.GuestRoomUi
import java.io.File
import java.net.URI
import kotlinx.coroutines.delay

private val GUEST_GREEN = Color(0xFF4ADE80)

/** `41:12`: whole minutes and seconds left (never negative). */
fun formatCountdown(msLeft: Long): String {
    val s = (msLeft.coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

/** The open room's time left as `mm:ss`, ticking each second; null while no room is open. */
@Composable
fun rememberRoomCountdown(ui: GuestRoomUi, clock: () -> Long = System::currentTimeMillis): String? {
    val expires = (ui as? GuestRoomUi.Open)?.expiresAtEpochMs ?: return null
    val text by produceState(formatCountdown(expires - clock()), expires) {
        while (true) {
            value = formatCountdown(expires - clock())
            delay(1000)
        }
    }
    return text
}

/**
 * The Guest room tab (stateless, so it can be tested and rendered headless). Layout per the `DesktopGuest` mockup.
 * [onDrop] gets files and folders dropped on the tab; [onOpenFolder] gets an [Exchanged.localPath].
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun GuestTabContent(
    ui: GuestRoomUi,
    exchanged: List<Exchanged>,
    onOpen: () -> Unit,
    onEnd: () -> Unit,
    onCopy: () -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onDrop: (List<File>) -> Unit,
    modifier: Modifier = Modifier,
    onOpenFolder: (String) -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
) {
    val target = remember(onDrop) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val data = event.dragData() as? DragData.FilesList ?: return false
                onDrop(data.readFiles().mapNotNull { runCatching { File(URI(it)) }.getOrNull() })
                return true
            }
        }
    }
    val open = ui as? GuestRoomUi.Open
    Box(modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { open != null }, target = target)) {
        if (open == null) ClosedRoom(ui, onOpen)
        else OpenRoom(open, exchanged, rememberRoomCountdown(open, clock) ?: "", onEnd, onCopy, onAddFiles, onAddFolder, onOpenFolder)
    }
}

@Composable
private fun PageTitle(text: String) = Text(text, style = TextStyle(fontFamily = ForgeFonts.grotesk700, fontSize = 22.sp, color = Forge.text))

@Composable
private fun ClosedRoom(ui: GuestRoomUi, onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("Guest room")
        val note = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted)
        Text("Works in any browser — nothing to install.", style = note)
        Text("Only this PC can exchange with the guest.", style = note)
        Text("Unclaimed files are deleted when the room closes.", style = note)
        (ui as? GuestRoomUi.Failed)?.let { Text(it.message, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.error)) }
        if (ui is GuestRoomUi.Opening) ForgeDisabledButton("Open a guest room", Modifier.width(260.dp))
        else ForgePrimaryButton("Open a guest room", onClick = onOpen, big = true, modifier = Modifier.width(260.dp))
    }
}

@Composable
private fun OpenRoom(
    room: GuestRoomUi.Open, exchanged: List<Exchanged>, left: String,
    onEnd: () -> Unit, onCopy: () -> Unit, onAddFiles: () -> Unit, onAddFolder: () -> Unit, onOpenFolder: (String) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageTitle("Guest room")
                if (room.guest != null) {
                    Text(
                        "Guest connected",
                        style = TextStyle(fontFamily = ForgeFonts.inter600, fontSize = 12.sp, color = Forge.onAccent),
                        modifier = Modifier.background(GUEST_GREEN, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("Closes in ", style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted))
                Text(left, style = TextStyle(fontFamily = ForgeFonts.grotesk700, fontSize = 13.sp, color = Forge.text))
                ForgeSecondaryButton("End room", onClick = onEnd, modifier = Modifier.height(36.dp))
            }
            room.error?.let { Text(it, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.error)) }
            ForgeSectionLabel("Exchanged")
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(exchanged, key = { it.id }) { ExchangedRow(it, onOpenFolder) }
            }
            DropArea(onAddFiles, onAddFolder)
        }
        InvitePanel(room.link, onCopy)
    }
}

@Composable
private fun ExchangedRow(item: Exchanged, onOpenFolder: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val inProgress = item.progress != null
    Column(Modifier.fillMaxWidth().background(Forge.surface, shape).padding(horizontal = 12.dp, vertical = if (inProgress) 10.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (inProgress) 0.dp else 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val color = if (item.fromGuest) Forge.accent else Forge.muted
            Text(
                if (item.fromGuest) "FROM GUEST" else "YOU SENT",
                style = TextStyle(fontFamily = ForgeFonts.inter600, fontSize = 11.sp, color = color),
                modifier = Modifier.border(1.dp, if (item.fromGuest) Forge.accent else Forge.dashed, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
            val detail = if (item.downloadedByGuest) listOf(item.detail, "downloaded by guest").filter { it.isNotEmpty() }.joinToString(" · ") else item.detail
            if (inProgress) {
                Text(item.title, style = TextStyle(fontFamily = ForgeFonts.inter500, fontSize = 14.sp, color = Forge.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(detail, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = Forge.muted), maxLines = 1)
            } else {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = TextStyle(fontFamily = ForgeFonts.inter500, fontSize = 14.sp, color = Forge.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(detail, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.fromGuest && item.localPath != null) ForgeSecondaryButton("Open folder", onClick = { onOpenFolder(item.localPath!!) }, modifier = Modifier.height(36.dp))
            }
        }
        item.progress?.let { p ->
            Box(Modifier.fillMaxWidth().height(6.dp).background(Forge.border, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(6.dp).background(Forge.accent, RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Composable
private fun DropArea(onAddFiles: () -> Unit, onAddFolder: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).background(Forge.strip, RoundedCornerShape(10.dp)).dashedBorder(Forge.dashed, 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text("Drop files or folders here to send them to the guest", style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeSecondaryButton("Add files…", onClick = onAddFiles, modifier = Modifier.height(36.dp))
            ForgeSecondaryButton("Add folder…", onClick = onAddFolder, modifier = Modifier.height(36.dp))
        }
    }
}

/** The 300 px side panel. Without a [link] (should not happen) it keeps only the notes. */
@Composable
private fun InvitePanel(link: String, onCopy: () -> Unit) {
    val note = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = Forge.muted)
    Column(
        Modifier.width(300.dp).fillMaxHeight().background(Forge.panel)
            .drawBehind { drawLine(Forge.border, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (link.isNotBlank()) {
            ForgeSectionLabel("Invite link")
            Text(
                link, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.text),
                modifier = Modifier.fillMaxWidth().background(Forge.surface, Forge.inputShape).border(1.dp, Forge.border, Forge.inputShape).padding(horizontal = 12.dp, vertical = 10.dp),
            )
            ForgePrimaryButton("Copy link", onClick = onCopy, big = true, modifier = Modifier.fillMaxWidth().height(44.dp))
            val qr = remember(link) { runCatching { qrImage(link) }.getOrNull() }
            if (qr != null) {
                Image(qr, contentDescription = "QR code for the invite link", filterQuality = FilterQuality.None, contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(168.dp).clip(Forge.inputShape))
            }
            Text("Scan with a phone camera, or send the link.\nWorks in any browser — nothing to install.", style = note.copy(textAlign = TextAlign.Center, lineHeight = 18.sp), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.weight(1f))
        Text("Only this PC can exchange with the guest.", style = note)
        Text("Unclaimed files are deleted when the room closes.", style = note)
    }
}
