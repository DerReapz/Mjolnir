package app.mjolnir.android.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.Messages
import app.mjolnir.client.TextPart
import app.mjolnir.client.linkify
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary

private val MineFill = Color(0xFF3B2A0E)
private val MineBorder = Color(0xFF5A4114)
private val Danger = Color(0xFFF87171)

private val BackIcon = strokeIcon("back", "M15 18l-6-6 6-6")
private val ShareIcon = strokeIcon("share", "M4 12v8h16v-8", "M12 3v13", "M7 8l5-5 5 5")
private val EditIcon = strokeIcon("edit", "M4 20h4L19 9l-4-4L4 16z")
private val DeleteIcon = strokeIcon("delete", "M5 7h14", "M10 3h4", "M7 7l1 13h8l1-13")

/** The text a Share… sends on: the message itself. */
internal fun shareText(m: ChatMessage): String = m.text.orEmpty()

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
    val nameOf = { id: String -> names[id] ?: members.firstOrNull { it.deviceId == id }?.name ?: messages.firstOrNull { it.author.deviceId == id }?.author?.name ?: "Someone" }
    Column(modifier.fillMaxSize().imePadding()) {
        val count = members.size
        Header(current.name, "${current.spaceName} · $count ${if (count == 1) "member" else "members"}", onBack, onSettings)
        notice?.let { NoticeBar(it, vm::dismissNotice) }
        val listState = rememberLazyListState()
        LaunchedEffect(current.id, messages.size) { if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex) } // the newest message
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                val event = m.event
                if (event != null) SystemLine(systemLine(event, nameOf))
                else MessageRow(
                    m, mine = m.author.deviceId == self, authorName = names[m.author.deviceId] ?: m.author.name,
                    canAct = messageActions(m, self, isAdmin).isNotEmpty(), onLongPress = { sheet = m },
                )
            }
        }
        if (current.member) {
            Composer(current, draft, editing != null, onChange = { vm.setDraft(current.id, it) }, onSend = vm::send, onCancel = vm::cancelEdit)
        } else if (isAdmin) {
            AdminBanner(onJoin = { vm.adminJoin(current.id) })
        }
    }
    sheet?.let { m ->
        MessageSheet(
            m, authorName = names[m.author.deviceId] ?: m.author.name, actions = messageActions(m, self, isAdmin), onDismiss = { sheet = null },
            onEdit = { vm.startEdit(m) }, onDelete = { vm.delete(m) },
        )
    }
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
private fun MessageRow(m: ChatMessage, mine: Boolean, authorName: String, canAct: Boolean, onLongPress: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxBubble = maxWidth * 0.8f
        Column(
            Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (!mine) Text(authorName, color = Forge.Muted, fontSize = 12.sp)
            val shape = if (mine) RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp) else RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
            val look = when {
                m.deleted -> Modifier.border(1.dp, Forge.Border, shape)
                mine -> Modifier.background(MineFill, shape).border(1.dp, MineBorder, shape)
                else -> Modifier.background(Forge.Surface, shape)
            }
            Box(
                Modifier.widthIn(max = maxBubble).then(look)
                    .then(if (canAct) Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = "Message actions") else Modifier)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (m.deleted) {
                    Text(bodyText(m), color = Forge.Muted, fontStyle = FontStyle.Italic, fontSize = 15.sp)
                } else {
                    Text(linked(m.text.orEmpty()), fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
            val meta = clockTime(m.sentAtEpochMs) + if (m.editedAtEpochMs != null && !m.deleted) " · ${Messages.EDITED}" else ""
            Text(meta, color = Forge.Muted, fontSize = 11.sp)
        }
    }
}

/** [text] with its links styled; the system opens them on tap. */
private fun linked(text: String): AnnotatedString = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = Forge.Accent, textDecoration = TextDecoration.Underline))
    for (part in linkify(text)) when (part) {
        is TextPart.Plain -> append(part.text)
        is TextPart.Link -> withLink(LinkAnnotation.Url(part.url, style)) { append(part.url) }
    }
}

/** The text field with *Send* (or *Save* / *Cancel* while editing); the draft lives in the view model, so a rotation keeps it. */
@Composable
private fun Composer(chat: ChatSummary, draft: String, editing: Boolean, onChange: (String) -> Unit, onSend: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Forge.SurfaceAlt).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = Forge.Surface) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${m.text.orEmpty().replace('\n', ' ')} · from $authorName", color = Forge.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
            )
            for (action in actions) when (action) {
                MessageAction.SHARE -> SheetRow(ShareIcon, Messages.SHARE) { onDismiss(); context.startActivity(shareLinkIntent(shareText(m))) }
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
