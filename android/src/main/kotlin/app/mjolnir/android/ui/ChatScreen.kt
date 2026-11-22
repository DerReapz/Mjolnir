package app.mjolnir.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.Messages
import app.mjolnir.client.Segment
import app.mjolnir.client.latestOwnMessage
import app.mjolnir.client.mentionQuery
import app.mjolnir.client.messageReceipts
import app.mjolnir.client.messageSegments
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.MessageReceipt
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Receipt

internal val MineFill = Color(0xFF3B2A0E)
internal val MineBorder = Color(0xFF5A4114)
private val Danger = Color(0xFFF87171)

private val BackIcon = strokeIcon("back", "M15 18l-6-6 6-6")
private val ShareIcon = strokeIcon("share", "M4 12v8h16v-8", "M12 3v13", "M7 8l5-5 5 5")
private val EditIcon = strokeIcon("edit", "M4 20h4L19 9l-4-4L4 16z")
private val InfoIcon = strokeIcon("info", "M12 11v6", "M12 7.5v.01", "M4 12a8 8 0 1016 0a8 8 0 10-16 0")
private val DeleteIcon = strokeIcon("delete", "M5 7h14", "M10 3h4", "M7 7l1 13h8l1-13")
private val DownloadIcon = strokeIcon("download", "M12 3v12", "M7 10l5 5 5-5", "M4 21h16")

/** The text a Share… sends on: the message itself. */
internal fun shareText(m: ChatMessage): String = m.text.orEmpty()

/** The FileProvider (manifest) that offers staged chat files to other apps for Share…. */
private fun chatFilesAuthority(context: Context) = context.packageName + ".chatfiles"

/** The system share sheet for a message's staged files (and its text), readable by the chosen app only. */
internal fun shareFilesIntent(context: Context, request: ShareRequest): Intent {
    val uris = request.files.map { (file, _) -> FileProvider.getUriForFile(context, chatFilesAuthority(context), file) }
    val types = request.files.map { it.second.ifBlank { "application/octet-stream" } }.distinct()
    val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    send.type = type
    request.text?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
    send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, null)
}

/** A conversation per the `PhoneChat` mockup: bubbles, a long-press sheet, and the composer. */
@Composable
fun ChatScreen(vm: ChatViewModel, onBack: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val chat by vm.chat.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val messages by vm.messages.collectAsState()
    val members by vm.members.collectAsState()
    val notice by vm.notice.collectAsState()
    val editing by vm.editingId.collectAsState()
    val names by vm.nicknameMap.collectAsState()
    val spaces by vm.spaces.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val pending by vm.pending.collectAsState()
    val info by vm.info.collectAsState()
    val shareRequest by vm.shareRequest.collectAsState()
    val context = LocalContext.current
    // "Saved to Downloads": a short toast, as Android does for finished downloads.
    LaunchedEffect(info) {
        info?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.dismissInfo() }
    }
    LaunchedEffect(shareRequest) {
        shareRequest?.let { request ->
            vm.shareHandled()
            try {
                context.startActivity(shareFilesIntent(context, request))
            } catch (_: ActivityNotFoundException) {
            } catch (_: IllegalArgumentException) { // the FileProvider refused a path
            }
        }
    }
    val current = chat
    if (current == null) {
        Column(modifier.fillMaxSize()) {
            Header("", "", onBack, null)
            if (loaded) Text("That chat is no longer there.", color = Forge.Muted, modifier = Modifier.padding(20.dp))
        }
        return
    }
    val draft by vm.draft(current.id).collectAsState()
    val isAdmin = vm.isSpaceAdmin(current)
    val self = vm.selfDeviceId
    var sheet by remember(current.id) { mutableStateOf<ChatMessage?>(null) }
    var infoFor by remember(current.id) { mutableStateOf<ChatMessage?>(null) }
    var viewing by remember(current.id) { mutableStateOf<Pair<ChatMessage, Attachment>?>(null) }
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.attach(uris.map { it.toString() })
    }
    val shownName = { p: Peer -> names[p.deviceId] ?: p.name }
    val latestOwn = remember(messages, self) { latestOwnMessage(messages, self) }
    val nameOf = { id: String -> names[id] ?: members.firstOrNull { it.deviceId == id }?.name ?: messages.firstOrNull { it.author.deviceId == id }?.author?.name ?: "Someone" }
    Column(modifier.fillMaxSize().imePadding()) {
        val count = members.size
        Header(current.name, "${current.spaceName} · $count ${if (count == 1) "member" else "members"}", onBack, onSettings)
        notice?.let { NoticeBar(it, vm::dismissNotice) }
        val listState = rememberLazyListState()
        // The newest message, unless the reader scrolled up to read older ones (our own sends always follow).
        var pendingShown by remember(current.id) { mutableIntStateOf(0) }
        LaunchedEffect(current.id, messages.size, pending.size) {
            val newSend = pending.size > pendingShown // a send of ours just queued (a failed one waiting doesn't pin the view)
            pendingShown = pending.size
            if (messages.isEmpty() && pending.isEmpty()) return@LaunchedEffect
            val ownLast = newSend || messages.lastOrNull()?.author?.deviceId == self
            val layout = listState.layoutInfo
            val nearBottom = layout.totalItemsCount == 0 || (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 2
            if (nearBottom || ownLast) listState.scrollToItem(messages.size + pending.size - 1)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                val event = m.event
                if (event != null) SystemLine(Messages.systemLine(event, nameOf))
                else MessageRow(
                    m, mine = m.author.deviceId == self, authorName = names[m.author.deviceId] ?: m.author.name,
                    canAct = messageActions(m, self, isAdmin).isNotEmpty(), onLongPress = { sheet = m },
                    segments = remember(m.text, m.mentions, members, names) { messageSegments(m.text.orEmpty(), m.mentions, members, shownName) },
                    mentionsMe = self != null && self in m.mentions,
                    receiptLine = if (m.id == latestOwn?.id) vm.receiptLine() else "",
                    media = vm.mediaSource, onOpen = { a -> viewing = m to a }, onDownload = { atts -> vm.download(m, atts) },
                )
            }
            items(pending, key = { "pending-${it.id}" }) { p -> PendingSendBubbles(p, onDismiss = { vm.dismissSend(p.id) }) }
        }
        if (current.member) {
            val query = mentionQuery(draft)
            val candidates = if (query == null) emptyList() else vm.mentionCandidates(query)
            if (candidates.isNotEmpty()) {
                MentionPicker(candidates, shownName) { p -> vm.pickMention(current.id, p) }
            }
            Composer(
                current, draft, editing != null, onChange = { vm.setDraft(current.id, it) }, onSend = vm::send, onCancel = vm::cancelEdit,
                onAttach = if (vm.mediaSource != null) ({ attach.launch(arrayOf("*/*")) }) else null,
            )
        } else if (isAdmin) {
            AdminBanner(onJoin = { vm.adminJoin(current.id) })
        }
    }
    sheet?.let { m ->
        MessageSheet(
            m, authorName = names[m.author.deviceId] ?: m.author.name, actions = messageActions(m, self, isAdmin), onDismiss = { sheet = null },
            onEdit = { vm.startEdit(m) }, onDelete = { vm.delete(m) }, onInfo = { infoFor = m },
            onDownload = { vm.download(m) }, onShareFiles = { vm.share(m) },
        )
    }
    infoFor?.let { m -> MessageInfoDialog(m, receipts, members, shownName, loadTimes = { vm.receiptTimes(m) }, onDismiss = { infoFor = null }) }
    viewing?.let { (m, a) ->
        ImageViewer(
            a, vm.mediaSource, onDownload = { vm.download(m, listOf(a)) },
            onShare = { vm.share(m.copy(attachments = listOf(a), text = null)) }, onClose = { viewing = null },
        )
    }
}

/** The members matching what follows the `@`, above the composer. */
@Composable
private fun MentionPicker(candidates: List<Peer>, shownName: (Peer) -> String, onPick: (Peer) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Forge.Surface).padding(horizontal = 8.dp, vertical = 4.dp).semantics { contentDescription = "Mention picker" }) {
        for (p in candidates) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(p) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(shownName(p), Modifier.weight(1f), color = Forge.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (p.kind == app.mjolnir.protocol.DeviceKind.PHONE) "Phone" else "PC", color = Forge.Muted, fontSize = 12.sp)
            }
        }
    }
}

/** Long-press → *Info*: who has read and who has only seen the message, with times. */
@Composable
private fun MessageInfoDialog(
    m: ChatMessage, receipts: List<Receipt>, members: List<Peer>, shownName: (Peer) -> String,
    loadTimes: suspend () -> List<MessageReceipt>, onDismiss: () -> Unit,
) {
    val r = messageReceipts(m, receipts, members)
    val times by produceState(emptyList<MessageReceipt>(), m.id) { value = loadTimes() } // this message's own times
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Forge.Surface,
        title = { Text(Messages.INFO) },
        text = {
            Column(Modifier.semantics { contentDescription = "Message info" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, list, at) in listOf(
                    Triple(Messages.READ, r.read, { rc: Receipt -> times.firstOrNull { it.deviceId == rc.deviceId }?.readAtEpochMs }),
                    Triple(Messages.SEEN, r.seen, { rc: Receipt -> times.firstOrNull { it.deviceId == rc.deviceId }?.seenAtEpochMs }),
                )) {
                    Text(label.uppercase(), color = Forge.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    if (list.isEmpty()) Text(Messages.NOBODY_YET, color = Forge.Muted, fontSize = 14.sp)
                    for (rc in list) {
                        val name = members.firstOrNull { it.deviceId == rc.deviceId }?.let(shownName) ?: continue
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, fontSize = 15.sp)
                            at(rc)?.let { Text(clockTime(it), color = Forge.Muted, fontSize = 13.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") } },
    )
}

@Composable
private fun Header(title: String, subtitle: String, onBack: () -> Unit, onSettings: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) {
            Icon(BackIcon, contentDescription = null)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Forge.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onSettings != null) {
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button, onClick = onSettings).semantics { contentDescription = "Chat settings" },
                contentAlignment = Alignment.Center,
            ) { Text("⋯", fontSize = 20.sp) }
        }
    }
    Box(Modifier.fillMaxWidth().size(1.dp).background(Forge.Border))
}

@Composable
private fun SystemLine(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, color = Forge.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    m: ChatMessage, mine: Boolean, authorName: String, canAct: Boolean, onLongPress: () -> Unit,
    segments: List<Segment>, mentionsMe: Boolean, receiptLine: String,
    media: ChatMediaSource?, onOpen: (Attachment) -> Unit, onDownload: (List<Attachment>) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxBubble = maxWidth * 0.8f
        Column(
            Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (!mine) Text(authorName, color = Forge.Muted, fontSize = 12.sp)
            if (!m.deleted && m.attachments.isNotEmpty()) {
                Column(
                    Modifier.widthIn(max = maxBubble), horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) { MessageAttachments(m, mine, media, onOpen, onDownload, onLongPress = if (canAct) onLongPress else null) }
            }
            // A message of only attachments has no empty text bubble.
            if (m.deleted || m.attachments.isEmpty() || !m.text.isNullOrBlank()) TextBubble(m, mine, canAct, onLongPress, segments, mentionsMe, maxBubble)
            val meta = clockTime(m.sentAtEpochMs) + if (m.editedAtEpochMs != null && !m.deleted) " · ${Messages.EDITED}" else ""
            Text(meta, color = Forge.Muted, fontSize = 11.sp)
            if (receiptLine.isNotEmpty()) Text(receiptLine, color = Forge.Muted, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextBubble(m: ChatMessage, mine: Boolean, canAct: Boolean, onLongPress: () -> Unit, segments: List<Segment>, mentionsMe: Boolean, maxBubble: Dp) {
    val shape = if (mine) RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp) else RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
    val look = when {
        m.deleted -> Modifier.border(1.dp, Forge.Border, shape)
        mine -> Modifier.background(MineFill, shape).border(1.dp, MineBorder, shape)
        else -> Modifier.background(Forge.Surface, shape)
    }
    // A message that mentions you gets an amber left edge.
    val edge = if (mentionsMe && !mine && !m.deleted) Modifier.clip(shape).drawBehind { drawRect(Forge.Accent, size = Size(3.dp.toPx(), size.height)) } else Modifier
    Box(
        Modifier.widthIn(max = maxBubble).then(look).then(edge)
            .then(if (canAct) Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = "Message actions") else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (m.deleted) {
            Text(bodyText(m), color = Forge.Muted, fontStyle = FontStyle.Italic, fontSize = 15.sp)
        } else {
            Text(linked(segments), fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

/** [segments] with links styled (the system opens them on tap) and mentions as amber chips. */
private fun linked(segments: List<Segment>): AnnotatedString = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = Forge.Accent, textDecoration = TextDecoration.Underline))
    val chip = SpanStyle(color = Forge.Accent, background = Forge.Accent.copy(alpha = 0.13f), fontWeight = FontWeight.SemiBold)
    for (seg in segments) {
        val url = seg.link
        if (url != null) withLink(LinkAnnotation.Url(url, style)) { append(seg.text) }
        else if (seg.mention) withStyle(chip) { append(seg.text) }
        else append(seg.text)
    }
}

/** The text field with *Send* (or *Save* / *Cancel* while editing); the draft lives in the view model, so a rotation keeps it. */
@Composable
private fun Composer(
    chat: ChatSummary, draft: String, editing: Boolean, onChange: (String) -> Unit, onSend: () -> Unit, onCancel: () -> Unit, onAttach: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().background(Forge.SurfaceAlt).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // + Attach sends the picked files to this chat at once (ruling CM-R5), as on the PC.
        if (onAttach != null && !editing) {
            Box(
                Modifier.size(56.dp).clip(ButtonShape).background(Forge.Surface, ButtonShape).border(1.dp, Forge.Border, ButtonShape)
                    .clickable(role = Role.Button, onClick = onAttach).semantics { contentDescription = "Attach" },
                contentAlignment = Alignment.Center,
            ) { Text("+", fontSize = 22.sp) }
        }
        OutlinedTextField(
            draft, onChange, Modifier.weight(1f), maxLines = 5, shape = FieldShape, colors = forgeFieldColors(),
            placeholder = { Text(Messages.messageChat(chat.name), color = Forge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        if (editing) OutlinedButton(onClick = onCancel, shape = ButtonShape, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
        Button(onClick = onSend, enabled = draft.isNotBlank(), shape = ButtonShape, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(if (editing) "Save" else "Send", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AdminBanner(onJoin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Forge.SurfaceAlt).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("You see this chat as the space admin. Join to write in it.", Modifier.weight(1f), color = Forge.Muted, fontSize = 13.sp)
        Button(onClick = onJoin, shape = ButtonShape, modifier = Modifier.heightIn(min = 48.dp)) { Text(Messages.JOIN, fontWeight = FontWeight.SemiBold) }
    }
}

/** The long-press sheet: the message and who sent it, then Share… and (where allowed) Edit and Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageSheet(
    m: ChatMessage, authorName: String, actions: List<MessageAction>, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onInfo: () -> Unit, onDownload: () -> Unit, onShareFiles: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = Forge.Surface) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${sheetSummary(m)} · from $authorName", color = Forge.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
            )
            for (action in actions) when (action) {
                MessageAction.DOWNLOAD -> SheetRow(DownloadIcon, Messages.DOWNLOAD) { onDismiss(); onDownload() }
                MessageAction.SHARE -> SheetRow(ShareIcon, Messages.SHARE) {
                    onDismiss()
                    if (m.attachments.isNotEmpty()) onShareFiles() else context.startActivity(shareLinkIntent(shareText(m)))
                }
                MessageAction.INFO -> SheetRow(InfoIcon, Messages.INFO) { onDismiss(); onInfo() }
                MessageAction.EDIT -> SheetRow(EditIcon, Messages.EDIT) { onDismiss(); onEdit() }
                MessageAction.DELETE -> SheetRow(DeleteIcon, Messages.DELETE, danger = true) { onDismiss(); onDelete() }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val ink = if (danger) Danger else Forge.Text
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(label, color = ink, fontSize = 16.sp)
    }
}
