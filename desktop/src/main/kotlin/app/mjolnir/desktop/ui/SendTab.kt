package app.mjolnir.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Messages
import app.mjolnir.client.destinationKey
import app.mjolnir.desktop.ItemPreview
import app.mjolnir.desktop.SendState
import app.mjolnir.desktop.detailLine
import app.mjolnir.desktop.previewOfLink
import app.mjolnir.desktop.uploadUnitKey
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.topFolder

/** Files shown together: a top folder (all its files) or one loose file. */
data class FileGroup(val name: String, val files: List<LocalUpload>) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

/** Groups by the folder each file was added from (absolute path), so two "Photos" folders stay separate. */
fun fileGroups(files: List<LocalUpload>): List<FileGroup> =
    files.groupBy(::uploadUnitKey).values.map { list ->
        FileGroup(topFolder(list.first().relativePath) ?: list.first().relativePath, list)
    }

/** One queued thing: a link, or a file/folder unit (all uploads that share [uploadUnitKey]). */
private sealed interface QueueRow {
    val key: String
    data class LinkRow(val url: String) : QueueRow { override val key get() = url }
    data class UnitRow(override val key: String, val files: List<LocalUpload>) : QueueRow {
        val isFolder get() = files.first().relativePath.contains('/')
        val name get() = topFolder(files.first().relativePath) ?: files.first().relativePath
    }
}

private fun queueRows(state: SendState): List<QueueRow> =
    state.links.map { QueueRow.LinkRow(it) } + state.files.groupBy(::uploadUnitKey).map { (k, v) -> QueueRow.UnitRow(k, v) }

/**
 * The Send tab (stateless, so it can be tested and rendered headless). Layout per the `DesktopSend` mockup.
 * [previews] holds what has been read from disk so far, keyed like [SendState.selectedRow].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SendTabContent(
    state: SendState,
    nicknameOf: (Peer) -> String,
    /** Applies a change to the controller's current queue (not to the snapshot this was composed from). */
    onUpdate: ((SendState) -> SendState) -> Unit,
    previews: Map<String, ItemPreview> = emptyMap(),
    sending: Boolean = false,
    onAddFiles: () -> Unit = {},
    onAddFolder: () -> Unit = {},
    onPair: () -> Unit = {},
    onSend: () -> Unit = {},
    onOpen: (ItemPreview) -> Unit = {},
    onShowInFolder: (ItemPreview) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val rows = queueRows(state)
    fun previewFor(row: QueueRow): ItemPreview? = when (row) {
        is QueueRow.LinkRow -> previews[row.key] ?: previewOfLink(row.url, null)
        is QueueRow.UnitRow -> previews[row.key]
    }

    Column(modifier.fillMaxSize()) {
        // Send to
        Column(
            Modifier.fillMaxWidth().drawBehind { drawLine(Forge.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ForgeSectionLabel("Send to")
            if (state.targets.isEmpty()) ForgeTextButton("Pair a device…", onClick = onPair)
            state.groups(nicknameOf).forEach { group ->
                group.title?.let { ForgeSectionLabel(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    group.peers.forEach { peer ->
                        val kind = when (peer.kind) { DeviceKind.PHONE -> "Phone"; DeviceKind.PC -> "PC"; DeviceKind.GUEST -> "Guest" }
                        // A device in two spaces has a card under each heading; the card clicked decides the space sent.
                        Column(
                            Modifier.widthIn(min = 170.dp, max = 260.dp).heightIn(min = 52.dp).clip(Forge.inputShape)
                                .forgeCard(state.isSelected(peer))
                                .clickable { onUpdate { it.selectTarget(peer.destinationKey) } }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(nicknameOf(peer), style = TextStyle(fontFamily = ForgeFonts.inter600, fontSize = 14.sp, color = Forge.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (peer.deviceId == state.lastUsedId) "$kind · last used" else kind,
                                style = MaterialTheme.typography.bodySmall.copy(color = Forge.muted), maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Queue
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ForgeSectionLabel("Sending · ${"${rows.size} ${if (rows.size == 1) "item" else "items"}"}")
                    Spacer(Modifier.weight(1f))
                    ForgeSecondaryButton("Add files…", onClick = onAddFiles)
                    Spacer(Modifier.width(8.dp))
                    ForgeSecondaryButton("Add folder…", onClick = onAddFolder)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { (if (it is QueueRow.LinkRow) "link:" else "unit:") + it.key }) { row ->
                        QueueRowView(
                            row, previewFor(row), selected = row.key == state.selectedRow, checked = row.key in state.selected,
                            expanded = row.key in expanded,
                            onSelect = { onUpdate { it.selectRow(row.key) } },
                            onCheck = { onUpdate { it.toggle(row.key) } },
                            onToggleExpanded = { expanded = if (row.key in expanded) expanded - row.key else expanded + row.key },
                            onRemove = (row as? QueueRow.UnitRow)?.let { unit ->
                                { onUpdate { cur -> unit.files.fold(cur) { s, f -> s.removeFile(f.path) }.copy(notice = null) } }
                            },
                        )
                    }
                }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).dashedBorder(Forge.dashed, 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Paste or drop links, files and folders anywhere in this window", style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted)) }
            }
            PreviewPanel(
                state.selectedRow?.let { key -> rows.find { it.key == key } }?.let(::previewFor),
                onOpen, onShowInFolder,
            )
        }

        // Bottom bar
        Row(
            Modifier.fillMaxWidth().background(Forge.strip).drawBehind { drawLine(Forge.border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(state.summary, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted), modifier = Modifier.weight(1f))
            val label = state.target?.let { "Send to ${nicknameOf(it)}" } ?: "Send"
            if (state.canSend && !sending) ForgePrimaryButton(label, onClick = onSend, big = true, modifier = Modifier.widthIn(max = 420.dp))
            else ForgeDisabledButton(label, Modifier.widthIn(max = 420.dp))
        }
    }
}

@Composable
private fun QueueRowView(
    row: QueueRow, preview: ItemPreview?, selected: Boolean, checked: Boolean, expanded: Boolean,
    onSelect: () -> Unit, onCheck: () -> Unit, onToggleExpanded: () -> Unit, onRemove: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Forge.surface, shape)
            .let { if (selected) it.border(2.dp, Forge.accent, shape) else it }
            .clickable(onClick = onSelect),
    ) {
        val folder = (row as? QueueRow.UnitRow)?.takeIf { it.isFolder }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Checkbox(checked = checked, onCheckedChange = { onCheck() }, colors = forgeCheckboxColors())
            Tile(row, preview)
            val (title, detail) = when (row) {
                is QueueRow.LinkRow -> ((preview as? ItemPreview.Link)?.title ?: row.url) to (preview?.let(::detailLine) ?: "")
                is QueueRow.UnitRow -> row.name to (preview?.let(::detailLine) ?: "${Messages.items(0, row.files.size)} · ${Messages.size(row.files.sumOf { it.sizeBytes })}")
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = TextStyle(fontFamily = ForgeFonts.inter500, fontSize = 14.sp, color = Forge.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (folder != null) ForgeTextButton(if (expanded) "Hide" else "Show", muted = true, onClick = onToggleExpanded)
            if (onRemove != null) ForgeTextButton("Remove", muted = true, onClick = onRemove)
        }
        if (folder != null && expanded) {
            val children = (preview as? ItemPreview.Folder)?.children.orEmpty()
            Column(Modifier.padding(start = 76.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                children.forEach { c ->
                    Text(childLine(c), style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** First letter of the site name (`en.wikipedia.org/x` gives `W`). */
internal fun siteLetter(hostPath: String?): String {
    val labels = hostPath?.substringBefore('/')?.split('.').orEmpty()
    val site = if (labels.size >= 2) labels[labels.size - 2] else labels.firstOrNull().orEmpty()
    return site.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "L"
}

private fun childLine(c: ItemPreview.FolderChild): String =
    if (c.isDir) "▸ ${c.name} · ${c.files} ${if (c.files == 1) "file" else "files"} · ${Messages.size(c.sizeBytes)}"
    else "${c.name} · ${Messages.size(c.sizeBytes)}"

/** The 44 px thumbnail or type badge. */
@Composable
private fun Tile(row: QueueRow, preview: ItemPreview?) {
    val badge = Color(0xFF262626)
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(badge), contentAlignment = Alignment.Center) {
        when {
            row is QueueRow.LinkRow -> {
                val letter = siteLetter((preview as? ItemPreview.Link)?.hostPath)
                Text(letter, style = TextStyle(fontFamily = ForgeFonts.grotesk700, fontSize = 18.sp, color = Forge.text))
            }
            preview is ItemPreview.Photo -> {
                val image = rememberThumbnail(preview.path, 88)
                if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else TypeBadge("IMG")
            }
            preview is ItemPreview.Folder || (row as? QueueRow.UnitRow)?.isFolder == true -> ForgeIcon(ICON_FOLDER, Forge.muted, 20.dp)
            preview is ItemPreview.OtherFile -> TypeBadge(preview.typeLabel)
            else -> TypeBadge("File")
        }
    }
}

@Composable
private fun TypeBadge(label: String) =
    Text(label.take(4), style = TextStyle(fontFamily = ForgeFonts.inter600, fontSize = 11.sp, color = Forge.muted), maxLines = 1)

@Composable
private fun PreviewPanel(preview: ItemPreview?, onOpen: (ItemPreview) -> Unit, onShow: (ItemPreview) -> Unit) {
    Column(
        Modifier.width(300.dp).fillMaxHeight().background(Forge.panel)
            .drawBehind { drawLine(Forge.border, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ForgeSectionLabel("Preview")
        if (preview == null) {
            Text("Select an item to preview it.", style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted))
            return@Column
        }
        val box = Modifier.fillMaxWidth().height200().clip(Forge.inputShape).background(Color(0xFF262626)).border(1.dp, Forge.border, Forge.inputShape)
        when (preview) {
            is ItemPreview.Photo -> Box(box, contentAlignment = Alignment.Center) {
                val image = rememberThumbnail(preview.path, 600)
                if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else Text("No preview", style = TextStyle(fontSize = 13.sp, color = Forge.muted))
            }
            is ItemPreview.Folder -> Column(box.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                preview.children.take(8).forEach { Text(childLine(it), style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (preview.children.size > 8) Text("… and ${preview.children.size - 8} more", style = TextStyle(fontSize = 13.sp, color = Forge.muted))
            }
            is ItemPreview.Link -> Box(box, contentAlignment = Alignment.Center) {
                Text(siteLetter(preview.hostPath), style = TextStyle(fontFamily = ForgeFonts.grotesk700, fontSize = 40.sp, color = Forge.text))
            }
            is ItemPreview.OtherFile -> Box(box, contentAlignment = Alignment.Center) { TypeBadge(preview.typeLabel) }
            is ItemPreview.Missing -> Box(box, contentAlignment = Alignment.Center) { TypeBadge("?") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val (name, where) = when (preview) {
                is ItemPreview.Photo -> preview.name to preview.path
                is ItemPreview.OtherFile -> preview.name to preview.path
                is ItemPreview.Folder -> preview.name to preview.path
                is ItemPreview.Link -> (preview.title ?: preview.url) to preview.url
                is ItemPreview.Missing -> preview.name to null
            }
            Text(name, style = TextStyle(fontFamily = ForgeFonts.inter600, fontSize = 14.sp, color = Forge.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detailLine(preview), style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = Forge.muted))
            if (where != null) Text(where, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = Forge.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (preview !is ItemPreview.Missing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeSecondaryButton("Open", onClick = { onOpen(preview) })
            if (preview !is ItemPreview.Link) ForgeSecondaryButton("Show in folder", onClick = { onShow(preview) })
        }
    }
}

private fun Modifier.height200() = this.then(Modifier.heightIn(min = 200.dp, max = 200.dp))

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
    drawRoundRect(color, cornerRadius = CornerRadius(radius.toPx()), style = stroke)
}
