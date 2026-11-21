package app.mjolnir.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.MentionPicks
import app.mjolnir.client.Messages
import app.mjolnir.client.Segment
import app.mjolnir.client.capCodePoints
import app.mjolnir.client.insertMention
import app.mjolnir.client.inviteCandidates
import app.mjolnir.client.latestOwnMessage
import app.mjolnir.client.mentionCandidates
import app.mjolnir.client.mentionQuery
import app.mjolnir.client.mentionSpans
import app.mjolnir.client.messageReceipts
import app.mjolnir.client.messageSegments
import app.mjolnir.client.receiptLineFor
import app.mjolnir.client.RelayException
import app.mjolnir.client.TextPart
import app.mjolnir.client.linkify
import app.mjolnir.desktop.AppController
import app.mjolnir.desktop.browseWithSystem
import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.InviteState
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.MessageReceipt
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Receipt
import app.mjolnir.protocol.Space
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val DANGER = Color(0xFFF87171)
private val MINE_FILL = Color(0xFF3B2A0E)
private val MINE_BORDER = Color(0xFF5A4114)

/** Everything the Chats tab shows, so the tab is stateless and can be tested and rendered headless. */
class ChatsUi(
    val chats: List<ChatSummary>,
    val invites: List<ChatInvite>,
    /** Per chat id, the messages above the read marker; chats without any are absent. */
    val unread: Map<String, Int>,
    val selfId: String?,
    /** The spaces this device administers: it sees their chats ("admin view") and may moderate them. */
    val adminSpaceIds: Set<String>,
    val selectedId: String?,
    /** The selected chat's messages, system entries included. */
    val messages: List<ChatMessage>,
    val members: List<Peer>,
    val nicknameOf: (Peer) -> String,
    val notice: String?,
    /** Who has seen and read how much of the selected chat. */
    val receipts: List<Receipt> = emptyList(),
    /** The chats with an unread message that mentions this device: the list marks them with an `@`. */
    val unreadMentions: Set<String> = emptySet(),
) {
    val selected: ChatSummary? get() = chats.firstOrNull { it.id == selectedId }

    /** Names a device id of an event in [message]: the members, the message's author, else "Someone". */
    fun nameResolver(message: ChatMessage): (String) -> String = { id ->
        (members.firstOrNull { it.deviceId == id } ?: message.author.takeIf { it.deviceId == id })?.let(nicknameOf) ?: "Someone"
    }
}

/** What the Chats tab can ask for. The suspending ones answer whether the relay took it, so the composer keeps its text on a refusal. */
class ChatsActions(
    val onSelect: (String) -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onApprove: (ChatInvite) -> Unit = {},
    val onDeny: (ChatInvite) -> Unit = {},
    val onJoin: (ChatInvite) -> Unit = {},
    val onDecline: (ChatInvite) -> Unit = {},
    /** Text and the device ids it mentions. */
    val onSend: suspend (String, List<String>) -> Boolean = { _, _ -> true },
    val onSaveEdit: suspend (ChatMessage, String, List<String>) -> Boolean = { _, _, _ -> true },
    /** When each member first saw and read the message, for its Info; empty when that can't be fetched. */
    val onLoadReceipts: suspend (ChatMessage) -> List<MessageReceipt> = { emptyList() },
    val onDeleteMessage: (ChatMessage) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onInvite: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onAdminJoin: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun clockTime(ms: Long): String = timeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** The list's timestamp: the time today, the weekday within a week, else the date. */
private fun listTime(ms: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
    return when {
        day == today -> clockTime(ms)
        day.isAfter(today.minusDays(7)) -> DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(day)
        else -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(day)
    }
}

private fun titleStyle(size: Int) = TextStyle(fontFamily = ForgeFonts.grotesk700, fontSize = size.sp, color = Forge.text)
private fun body(size: Int = 14, family: androidx.compose.ui.text.font.FontFamily = ForgeFonts.inter400, color: Color = Forge.text) =
    TextStyle(fontFamily = family, fontSize = size.sp, color = color)

/** The accent pill used for unread counts (the sidebar's too). */
@Composable
internal fun ForgeBadge(text: String) = Text(
    text, style = body(11, ForgeFonts.inter600, Forge.onAccent),
    modifier = Modifier.background(Forge.accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
)

/** A compact button at the mockup's 32 px (34 px for [tall]), primary (accent) or outlined; disabled ones keep their place but do nothing. */
@Composable
internal fun ChatButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
    enabled: Boolean = true, danger: Boolean = false, tall: Boolean = false,
) {
    val shape = Forge.buttonShape
    val fill = if (primary) (if (enabled) Forge.accent else Forge.accent.copy(alpha = 0.3f)) else Color.Transparent
    val ink = when { primary -> Forge.onAccent; danger -> DANGER; enabled -> Forge.text; else -> Forge.muted }
    val line = when { primary -> Color.Transparent; danger -> Color(0xFF5B2626); else -> Forge.border }
    Box(
        modifier.heightIn(min = if (tall) 34.dp else 32.dp).clip(shape).background(fill, shape).border(1.dp, line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = body(13, if (primary) ForgeFonts.inter600 else ForgeFonts.inter400, ink), maxLines = 1) }
}

/** A dashed rounded outline, as around the mockup's invitation card. */
private fun Modifier.dashedBorder(color: Color, radius: Float = 10f): Modifier = drawBehind {
    val stroke = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    drawRoundRect(color, Offset(0.5f, 0.5f), Size(size.width - 1f, size.height - 1f), CornerRadius(radius.dp.toPx()), style = stroke)
}

/** The Chats tab: the list (approvals on top, invitations at the bottom) and the open conversation. Layout per the `DesktopChats` mockup. */
@Composable
fun ChatsTabContent(ui: ChatsUi, actions: ChatsActions, now: () -> Long = System::currentTimeMillis) {
    Row(Modifier.fillMaxSize().background(Forge.background)) {
        ChatList(ui, actions, now)
        Box(Modifier.width(1.dp).fillMaxHeight().background(Forge.border))
        Box(Modifier.weight(1f).fillMaxHeight()) { Conversation(ui, actions) }
    }
}

// ---- the list ----

@Composable
private fun ChatList(ui: ChatsUi, actions: ChatsActions, now: () -> Long) {
    val approvals = ui.invites.filter { it.state == InviteState.PENDING_APPROVAL }
    val invitations = ui.invites.filter { it.state == InviteState.APPROVED }
    val groups = ui.chats.groupBy { it.spaceId }.values
        .sortedBy { it.first().spaceName.lowercase() }
        .map { chats -> chats.sortedWith(compareByDescending<ChatSummary> { it.lastMessage?.sentAtEpochMs ?: 0L }.thenBy { it.name.lowercase() }) }
    Column(Modifier.width(270.dp).fillMaxHeight().background(Forge.panel)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", style = titleStyle(20), modifier = Modifier.weight(1f))
            ChatButton(Messages.NEW_CHAT_PC, actions.onNewChat, tall = true)
        }
        for (invite in approvals) ApprovalCard(invite, ui.nicknameOf, actions)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            for (chats in groups) {
                item(key = "space-${chats.first().spaceId}") {
                    Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp)) { ForgeSectionLabel(chats.first().spaceName) }
                }
                items(chats, key = { it.id }) { chat ->
                    ChatListItem(chat, selected = chat.id == ui.selectedId, unread = ui.unread[chat.id] ?: 0, mention = chat.id in ui.unreadMentions, ui, now, onClick = { actions.onSelect(chat.id) })
                }
            }
        }
        for (invite in invitations) InvitationCard(invite, actions)
    }
}

@Composable
private fun ChatListItem(chat: ChatSummary, selected: Boolean, unread: Int, mention: Boolean, ui: ChatsUi, now: () -> Long, onClick: () -> Unit) {
    val last = chat.lastMessage
    val preview = when {
        last == null -> "No messages yet"
        last.event != null -> Messages.systemLine(last.event!!, ui.nameResolver(last))
        else -> "${ui.nicknameOf(last.author)}: ${last.text.orEmpty()}"
    }
    Column(
        Modifier.fillMaxWidth().background(if (selected) Forge.surface else Color.Transparent).clickable(onClick = onClick)
            .drawBehind { if (selected) drawRect(Forge.accent, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 17.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(chat.name, Modifier.weight(1f, fill = false), style = body(14, ForgeFonts.inter600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!chat.member) {
                    Text(
                        Messages.ADMIN_VIEW, style = body(11, color = Forge.muted), maxLines = 1,
                        modifier = Modifier.border(1.dp, Forge.dashed, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
                    )
                }
            }
            if (mention) Text("@", style = body(13, ForgeFonts.inter600, Forge.accent), modifier = Modifier.semantics { contentDescription = "Unread mention" })
            if (unread > 0) ForgeBadge(unread.toString())
            else if (last != null) Text(listTime(last.sentAtEpochMs, now()), style = body(11, color = Forge.muted))
        }
        Text(preview, style = body(12, color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `{inviter} wants to add {name} to {chat}` for the owner, with *Approve* / *Deny*. */
@Composable
private fun ApprovalCard(invite: ChatInvite, nicknameOf: (Peer) -> String, actions: ChatsActions) {
    val inviter = nicknameOf(invite.inviter)
    val invitee = nicknameOf(invite.invitee)
    val text = buildAnnotatedString {
        val sentence = Messages.inviteApproval(inviter, invitee) + " to ${invite.chatName}"
        append(sentence)
        val bold = SpanStyle(fontFamily = ForgeFonts.inter600)
        addStyle(bold, 0, inviter.length)
        val nameAt = inviter.length + " wants to add ".length
        addStyle(bold, nameAt, nameAt + invitee.length)
    }
    Column(
        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp).fillMaxWidth().background(Forge.surface, Forge.inputShape)
            .border(1.dp, Forge.accent, Forge.inputShape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = body(13))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatButton(Messages.APPROVE, { actions.onApprove(invite) }, Modifier.weight(1f), primary = true)
            ChatButton(Messages.DENY, { actions.onDeny(invite) }, Modifier.weight(1f))
        }
    }
}

/** `You're invited to {chat}` (its space) with *Join* / *Decline*. */
@Composable
private fun InvitationCard(invite: ChatInvite, actions: ChatsActions) {
    val text = buildAnnotatedString {
        val head = Messages.invitedTo(invite.chatName)
        append(head)
        addStyle(SpanStyle(fontFamily = ForgeFonts.inter600, color = Forge.text), head.length - invite.chatName.length, head.length)
        append(" (${invite.spaceName})")
    }
    Column(
        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp).fillMaxWidth().dashedBorder(Forge.dashed)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = body(12, color = Forge.muted))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatButton(Messages.JOIN, { actions.onJoin(invite) }, Modifier.weight(1f), primary = true)
            ChatButton(Messages.DECLINE, { actions.onDecline(invite) }, Modifier.weight(1f))
        }
    }
}

// ---- the conversation ----

@Composable
private fun Conversation(ui: ChatsUi, actions: ChatsActions) {
    val chat = ui.selected
    if (chat == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (ui.chats.isEmpty()) "No chats yet. Start one with New chat…" else "Choose a chat", style = body(14, color = Forge.muted))
        }
        return
    }
    val isAdmin = chat.spaceId in ui.adminSpaceIds
    var editing by remember(chat.id) { mutableStateOf<ChatMessage?>(null) }
    var info by remember(chat.id) { mutableStateOf<ChatMessage?>(null) }
    val latestOwn = remember(ui.messages, ui.selfId) { latestOwnMessage(ui.messages, ui.selfId) }
    val names = remember(ui.members, ui.messages) {
        (ui.messages.map { it.author } + ui.members).associateBy { it.deviceId } + ui.members.associateBy { it.deviceId }
    }
    val nameOf = { id: String -> names[id]?.let(ui.nicknameOf) ?: "Someone" }
    Column(Modifier.fillMaxSize()) {
        ConversationHeader(chat, ui, actions)
        ui.notice?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), style = body(13, color = Forge.error))
                ForgeTextButton("Close", muted = true, onClick = actions.onDismissNotice)
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(chat.id, ui.messages.size) { if (ui.messages.isNotEmpty()) listState.scrollToItem(ui.messages.lastIndex) } // the newest message
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ui.messages, key = { it.id }) { m ->
                val event = m.event
                if (event != null) SystemLine(Messages.systemLine(event, nameOf))
                else MessageRow(
                    m, mine = m.author.deviceId == ui.selfId, byAdmin = isAdmin, ui, actions, onEdit = { editing = it }, onInfo = { info = it },
                    receiptLine = if (m.id == latestOwn?.id) receiptLineOf(m, ui) else "",
                )
            }
        }
        if (chat.member) {
            Composer(chat, editing, ui, actions, onDone = { editing = null })
        } else if (isAdmin) {
            Row(
                Modifier.fillMaxWidth().background(Forge.strip).drawBehind { drawLine(Forge.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("You see this chat as the space admin. Join to write in it.", Modifier.weight(1f), style = body(13, color = Forge.muted))
                ChatButton(Messages.JOIN, actions.onAdminJoin, primary = true, tall = true)
            }
        }
    }
    info?.let { MessageInfoDialog(it, ui, actions, onClose = { info = null }) }
}

/** The line under your latest message ("Read by Ann · Seen by Bo"), or empty while nobody has seen it. */
private fun receiptLineOf(m: ChatMessage, ui: ChatsUi): String = receiptLineFor(m, ui.receipts, ui.members, ui.nicknameOf)

/** ⋯ → *Info*: who has read and who has only seen the message, with times. */
@Composable
private fun MessageInfoDialog(m: ChatMessage, ui: ChatsUi, actions: ChatsActions, onClose: () -> Unit) {
    val r = messageReceipts(m, ui.receipts, ui.members)
    val times by produceState(emptyList<MessageReceipt>(), m.id) { value = actions.onLoadReceipts(m) } // this message's own times, not the member's latest
    ForgeModal(onDismiss = onClose) {
        Column(Modifier.semantics { contentDescription = "Message info" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Messages.INFO, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            for ((label, list, at) in listOf(
                Triple(Messages.READ, r.read, { rc: Receipt -> times.firstOrNull { it.deviceId == rc.deviceId }?.readAtEpochMs }),
                Triple(Messages.SEEN, r.seen, { rc: Receipt -> times.firstOrNull { it.deviceId == rc.deviceId }?.seenAtEpochMs }),
            )) {
                ForgeSectionLabel(label)
                if (list.isEmpty()) Text(Messages.NOBODY_YET, style = body(13, color = Forge.muted))
                for (rc in list) {
                    val name = ui.members.firstOrNull { it.deviceId == rc.deviceId }?.let(ui.nicknameOf) ?: continue
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, style = body(14))
                        at(rc)?.let { Text(clockTime(it), style = body(13, color = Forge.muted)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ForgeSecondaryButton("Close", onClick = onClose) }
        }
    }
}

@Composable
private fun ConversationHeader(chat: ChatSummary, ui: ChatsUi, actions: ChatsActions) {
    val owner = if (chat.ownerId == ui.selfId) "you" else ui.members.firstOrNull { it.deviceId == chat.ownerId }?.let(ui.nicknameOf) ?: "someone"
    val count = ui.members.size
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(Forge.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(chat.name, style = titleStyle(17), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${chat.spaceName} · $count ${if (count == 1) "member" else "members"} · owner: $owner", style = body(12, color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (chat.member) ChatButton(Messages.INVITE, actions.onInvite, tall = true)
        Box(
            Modifier.size34().clip(Forge.buttonShape).background(Forge.surface, Forge.buttonShape).border(1.dp, Forge.border, Forge.buttonShape)
                .semantics { contentDescription = "Chat settings" }.clickable(role = Role.Button, onClick = actions.onSettings),
            contentAlignment = Alignment.Center,
        ) { Text("⋯", style = body(16)) }
    }
}

private fun Modifier.size34() = this.then(Modifier.width(34.dp).heightIn(min = 34.dp))

@Composable
private fun SystemLine(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, style = body(12, color = Forge.muted), textAlign = TextAlign.Center)
    }
}

private class MenuItem(val label: String, val danger: Boolean, val onClick: () -> Unit)

/** One message: the bubble with its author and time, and the ⋯ menu beside it. */
@Composable
private fun MessageRow(
    m: ChatMessage, mine: Boolean, byAdmin: Boolean, ui: ChatsUi, actions: ChatsActions, onEdit: (ChatMessage) -> Unit,
    onInfo: (ChatMessage) -> Unit, receiptLine: String,
) {
    var menuOpen by remember(m.id) { mutableStateOf(false) }
    val firstLink = remember(m.text) { m.text?.let { t -> linkify(t).filterIsInstance<TextPart.Link>().firstOrNull()?.url } }
    val items = buildList {
        if (!m.deleted) {
            firstLink?.let { add(MenuItem(Messages.COPY_LINK, false) { actions.onCopy(it) }) }
            if (mine && m.text != null) add(MenuItem(Messages.INFO, false) { onInfo(m) })
            if (mine && m.text != null) add(MenuItem(Messages.EDIT, false) { onEdit(m) })
            if (mine || byAdmin) add(MenuItem(Messages.DELETE, true) { actions.onDeleteMessage(m) })
        }
    }
    val menu: @Composable () -> Unit = {
        if (items.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.padding(horizontal = 4.dp).size34().clip(Forge.buttonShape)
                        .semantics { contentDescription = "Message menu" }.clickable(role = Role.Button) { menuOpen = !menuOpen },
                    contentAlignment = Alignment.Center,
                ) { Text("⋯", style = body(16, color = Forge.muted)) }
                if (menuOpen) {
                    Column(
                        Modifier.width(170.dp).background(Forge.surface, RoundedCornerShape(10.dp)).border(1.dp, Forge.dashed, RoundedCornerShape(10.dp)).padding(6.dp),
                    ) {
                        for (item in items) {
                            Text(
                                item.label, style = body(13, color = if (item.danger) DANGER else Forge.text),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(RoundedCornerShape(6.dp))
                                    .clickable(role = Role.Button) { menuOpen = false; item.onClick() }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxBubble = maxWidth * 0.7f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
            if (mine) menu()
            Column(Modifier.widthIn(max = maxBubble), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (!mine) Text(ui.nicknameOf(m.author), style = body(12, color = Forge.muted))
                Bubble(m, mine, ui, actions)
                val meta = clockTime(m.sentAtEpochMs) + if (m.editedAtEpochMs != null && !m.deleted) " · ${Messages.EDITED}" else ""
                Text(meta, style = body(11, color = Forge.muted))
                if (receiptLine.isNotEmpty()) Text(receiptLine, style = body(11, color = Forge.muted), textAlign = TextAlign.End)
            }
            if (!mine) menu()
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, mine: Boolean, ui: ChatsUi, actions: ChatsActions) {
    val shape = if (mine) RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp) else RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp)
    val mentionsMe = !m.deleted && ui.selfId != null && ui.selfId in m.mentions && !mine
    val base = when {
        m.deleted -> Modifier.border(1.dp, Forge.border, shape)
        mine -> Modifier.background(MINE_FILL, shape).border(1.dp, MINE_BORDER, shape)
        else -> Modifier.background(Forge.surface, shape)
    }
    // A message that mentions you gets an amber left edge.
    val modifier = if (mentionsMe) base.clip(shape).drawBehind { drawRect(Forge.accent, size = Size(3.dp.toPx(), size.height)) } else base
    Box(modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
        if (m.deleted) {
            Text(
                if (m.deletedByAdmin) Messages.DELETED_BY_ADMIN else Messages.MESSAGE_DELETED,
                style = body(14, color = Forge.muted).copy(fontStyle = FontStyle.Italic),
            )
        } else {
            val segments = remember(m.text, m.mentions, ui.members, ui.nicknameOf) { messageSegments(m.text.orEmpty(), m.mentions, ui.members, ui.nicknameOf) }
            Text(linked(segments, actions.onOpenLink), style = body(14).copy(lineHeight = 20.sp))
        }
    }
}

/** [segments] with links styled and opening through [open], and mentions as amber chips. */
private fun linked(segments: List<Segment>, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = Forge.accent, textDecoration = TextDecoration.Underline))
    val chip = SpanStyle(color = Forge.accent, background = Forge.accentGlow, fontFamily = ForgeFonts.inter600)
    for (seg in segments) {
        val url = seg.link
        if (url != null) withLink(LinkAnnotation.Url(url, style) { open(url) }) { append(seg.text) }
        else if (seg.mention) withStyle(chip) { append(seg.text) }
        else append(seg.text)
    }
}

// ---- the composer ----

/** The text field with *Send* (or *Save* / *Cancel* while [editing] a message); Enter sends, Shift+Enter breaks the line; 4,000 code points at most. */
@Composable
private fun Composer(chat: ChatSummary, editing: ChatMessage?, ui: ChatsUi, actions: ChatsActions, onDone: () -> Unit) {
    // Editing starts from the text as shown here (nicknames), with the message's mentions already picked.
    val start = remember(chat.id, editing?.id) { editing?.let { mentionSpans(it.text.orEmpty(), it.mentions, ui.members, ui.nicknameOf).text }.orEmpty() }
    var text by remember(chat.id, editing?.id) { mutableStateOf(start) }
    // Only members picked from the picker are mentions; typing "@Name" yourself is plain text.
    var picks by remember(chat.id, editing?.id) { mutableStateOf(editing?.let(MentionPicks::of) ?: MentionPicks()) }
    val query = mentionQuery(text)
    val candidates = if (query == null) emptyList() else mentionCandidates(ui.members, ui.selfId, query, ui.nicknameOf)
    fun pick(peer: Peer) {
        val shown = ui.nicknameOf(peer)
        text = capCodePoints(insertMention(text, shown), Limits.MAX_CHAT_TEXT_LENGTH)
        picks = picks.with(peer.deviceId)
    }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canSend = text.isNotBlank() && !busy
    fun submit() {
        if (!canSend) return
        val (sending, mentions) = picks.outgoing(text, ui.members, ui.selfId, ui.nicknameOf)
        busy = true
        scope.launch {
            try {
                val ok = if (editing != null) actions.onSaveEdit(editing, sending, mentions) else actions.onSend(sending.trim(), mentions)
                if (ok) { text = ""; picks = MentionPicks(); onDone() }
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
    if (candidates.isNotEmpty()) MentionPicker(candidates, ui.nicknameOf, ::pick)
    Row(
        Modifier.fillMaxWidth().background(Forge.strip).drawBehind { drawLine(Forge.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = text, onValueChange = { text = capCodePoints(it, Limits.MAX_CHAT_TEXT_LENGTH) },
            textStyle = body(14), cursorBrush = SolidColor(Forge.accent),
            modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                    if (candidates.isNotEmpty()) pick(candidates.first()) else submit() // Enter takes the first match while the picker is open
                    true
                } else false
            },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 140.dp).background(Forge.surface, Forge.inputShape)
                        .border(1.dp, Forge.border, Forge.inputShape).padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text(Messages.messageChat(chat.name), style = body(14, color = Forge.muted), maxLines = 1)
                    inner()
                }
            },
        )
        if (editing != null) {
            ChatButton("Cancel", onDone, Modifier.heightIn(min = 40.dp))
            ChatButton("Save", ::submit, Modifier.heightIn(min = 40.dp), primary = true, enabled = canSend)
        } else {
            Box(Modifier.heightIn(min = 40.dp)) { ChatButton("Send", ::submit, Modifier.heightIn(min = 40.dp), primary = true, enabled = canSend) }
        }
    }
    }
}

/** The members matching what follows the `@`, above the composer. */
@Composable
private fun MentionPicker(candidates: List<Peer>, nicknameOf: (Peer) -> String, onPick: (Peer) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).background(Forge.surface, RoundedCornerShape(10.dp))
            .border(1.dp, Forge.dashed, RoundedCornerShape(10.dp)).padding(6.dp).semantics { contentDescription = "Mention picker" },
    ) {
        for (peer in candidates) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { onPick(peer) }.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(nicknameOf(peer), Modifier.weight(1f), style = body(13, color = Forge.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kindLabel(peer), style = body(12, color = Forge.muted))
            }
        }
    }
}

// ---- the stateful tab ----

private sealed interface ChatDialog {
    data object Settings : ChatDialog
    data object Invite : ChatDialog
    data object NewChat : ChatDialog
}

/** The Chats tab wired to the [AppController]: its chat list, the chat on screen (read while visible), and the dialogs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsTab(c: AppController) {
    val chats by c.chats.chats.collectAsState()
    val invites by c.chats.invites.collectAsState()
    val unread by c.chats.unread.collectAsState()
    val unreadMentions by c.chats.unreadMentions.collectAsState()
    val selectedId by c.selectedChat.collectAsState()
    val spaces by c.spaces.spaces.collectAsState()
    val config by c.config.collectAsState()
    val nicknames by c.nicknames.changes.collectAsState()
    val notice by c.chatNotice.collectAsState()
    val showing by c.windowShowing.collectAsState()
    val selected = chats.firstOrNull { it.id == selectedId }
    val id = selected?.id
    val messages by remember(id) { id?.let(c.chats::messages) ?: MutableStateFlow(emptyList()) }.collectAsState()
    val members by remember(id) { id?.let(c.chats::members) ?: MutableStateFlow(emptyList()) }.collectAsState()
    val receipts by remember(id) { id?.let(c.chats::receipts) ?: MutableStateFlow(emptyList()) }.collectAsState()
    var dialog by remember { mutableStateOf<ChatDialog?>(null) }
    val nicknameOf = { p: Peer -> nicknames.nicknameOr(p.deviceId, p.name) }
    val adminSpaceIds = spaces.filter { it.adminId == config.deviceId }.mapTo(HashSet()) { it.id }

    LaunchedEffect(Unit) { c.chats.refresh(); c.chats.refreshInvites(); c.spaces.refresh() }
    // The chat on screen is open: its messages are read, and do not notify.
    DisposableEffect(id, showing) { // a minimised window shows nothing: not open, not read
        if (id != null && showing) c.chats.openChat.value = id
        onDispose { c.chats.openChat.compareAndSet(id, null) }
    }
    LaunchedEffect(id) { if (id != null) c.chats.sync(id) }
    LaunchedEffect(id, messages.lastOrNull()?.rev, selected?.member, showing) {
        if (id != null && showing && selected?.member == true) try { c.chats.markRead(id) } catch (_: RelayException) { /* the next change tries again */ }
    }

    fun run(block: suspend app.mjolnir.client.ChatController.() -> Unit) { c.scope.launch { c.chatCall(block) } }
    val actions = ChatsActions(
        onSelect = c::selectChat,
        onNewChat = { dialog = ChatDialog.NewChat },
        onApprove = { i -> run { approveInvite(i.id) } },
        onDeny = { i -> run { denyInvite(i.id) } },
        onJoin = { i -> c.scope.launch { c.chatCall { joinInvite(i.id) }?.let { c.selectChat(i.chatId) } } },
        onDecline = { i -> run { declineInvite(i.id) } },
        onSend = { text, mentions -> id != null && c.chatCall { send(id, text, mentions) } != null },
        onSaveEdit = { m, text, mentions -> c.chatCall { edit(m.chatId, m.id, text, mentions) } != null },
        onLoadReceipts = { m -> try { c.chats.messageReceipts(m.chatId, m.id) } catch (_: RelayException) { emptyList() } },
        onDeleteMessage = { m -> run { delete(m.chatId, m.id) } },
        onCopy = ::copyToClipboard,
        onOpenLink = { url -> c.scope.launch(Dispatchers.IO) { try { browseWithSystem(URI(url)) } catch (_: Exception) {} } },
        onInvite = { dialog = ChatDialog.Invite },
        onSettings = { dialog = ChatDialog.Settings },
        onAdminJoin = { if (id != null) run { adminJoinChat(id) } },
        onDismissNotice = c::dismissChatNotice,
    )

    Box(Modifier.fillMaxSize()) {
        ChatsTabContent(ChatsUi(chats, invites, unread, config.deviceId, adminSpaceIds, selectedId, messages, members, nicknameOf, notice, receipts, unreadMentions), actions)
        val close = { dialog = null }
        when (dialog) {
            null -> {}
            ChatDialog.NewChat -> NewChatDialog(spaces, onCreate = { space, name -> c.createChat(space, name); close() }, onClose = close)
            ChatDialog.Invite -> if (selected != null) {
                val space = spaces.firstOrNull { it.id == selected.spaceId }
                ChatInviteDialog(
                    selected.name, selected.spaceName, isOwner = selected.ownerId == config.deviceId,
                    candidates = space?.let { inviteCandidates(it, members, config.deviceId) }.orEmpty(), nicknameOf = nicknameOf,
                    onInvite = { picked -> picked.forEach { p -> run { inviteToChat(selected.id, p.deviceId) } }; close() },
                    onClose = close,
                )
            }
            ChatDialog.Settings -> if (selected != null) {
                ChatSettingsDialog(
                    ChatSettings(
                        selected, members, invites.filter { it.chatId == selected.id }, config.deviceId, selected.spaceId in adminSpaceIds, nicknameOf,
                        ChatSettingsActions(
                            onRename = { name -> run { renameChat(selected.id, name) } },
                            onMakeOwner = { p -> run { transferChatOwner(selected.id, p.deviceId) } },
                            onRemove = { p -> run { removeChatMember(selected.id, p.deviceId) } },
                            onLeave = { run { leaveChat(selected.id) }; close() },
                            onDelete = { run { deleteChat(selected.id) }; close() },
                            onApprove = { i -> run { approveInvite(i.id) } },
                            onDeny = { i -> run { denyInvite(i.id) } },
                        ),
                    ),
                    onClose = close,
                )
            }
        }
    }
}

/** *New chat…* from the Chats tab: pick the space (when there are several) and name the chat. */
@Composable
private fun NewChatDialog(spaces: List<Space>, onCreate: (Space, String) -> Unit, onClose: () -> Unit) {
    var picked by remember { mutableStateOf(spaces.firstOrNull()) }
    var name by remember { mutableStateOf("") }
    val valid = picked != null && name.isNotBlank()
    ForgeModal(onDismiss = onClose) {
        Text("New chat", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        if (spaces.isEmpty()) {
            Text("Chats live in a space. Join or create a space on the Devices tab first.", style = body(13, color = Forge.muted))
        } else {
            if (spaces.size > 1) {
                ForgeSectionLabel("Space")
                for (space in spaces) {
                    val on = space.id == picked?.id
                    Text(
                        space.name, style = body(14, if (on) ForgeFonts.inter600 else ForgeFonts.inter400, if (on) Forge.accent else Forge.text),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).background(if (on) Forge.panel else Color.Transparent, Forge.rowShape)
                            .clickable(role = Role.RadioButton) { picked = space }.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            ForgeInput(name, { name = it.take(Limits.MAX_SPACE_NAME_LENGTH) }, placeholder = "Holiday planning", onEnter = { if (valid) onCreate(picked!!, name.trim()) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            ForgeSecondaryButton("Cancel", onClick = onClose)
            ForgePrimaryButton("Create", enabled = valid, onClick = { onCreate(picked!!, name.trim()) })
        }
    }
}

/** The picker's label for a device's kind. */
internal fun kindLabel(peer: Peer) = if (peer.kind == DeviceKind.PHONE) "Phone" else "PC"
