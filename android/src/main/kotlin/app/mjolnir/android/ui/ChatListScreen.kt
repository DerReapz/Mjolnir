package app.mjolnir.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.Messages
import app.mjolnir.client.Presence
import app.mjolnir.protocol.ChatKind
import app.mjolnir.protocol.ChatSummary
import kotlinx.coroutines.delay
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val AvatarUnread = androidx.compose.ui.graphics.Color(0xFF3B2A0E)
private val MutedBadge = androidx.compose.ui.graphics.Color(0xFF4A4A4A)
private val OnlineDot = androidx.compose.ui.graphics.Color(0xFF3FB950)
private val OfflineDot = androidx.compose.ui.graphics.Color(0xFF6E7681)
private val AvatarIdle = androidx.compose.ui.graphics.Color(0xFF262626)

/** The Chats tab: the list, and over it the open chat or its settings. Back steps out one level. */
@Composable
fun ChatsScreen(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val selected by vm.selectedId.collectAsState()
    var settings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(selected) { if (selected == null) settings = false }
    BackHandler(enabled = selected != null) { if (settings) settings = false else vm.close() }
    when {
        selected != null && settings -> ChatSettingsScreen(vm, onBack = { settings = false }, modifier)
        selected != null -> ChatScreen(vm, onBack = vm::close, onSettings = { settings = true }, modifier)
        else -> ChatListScreen(vm, modifier)
    }
}

@Composable
fun ChatListScreen(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val ui by vm.list.collectAsState()
    val spaces by vm.spaces.collectAsState()
    val notice by vm.notice.collectAsState()
    val names by vm.nicknameMap.collectAsState()
    var creating by rememberSaveable { mutableStateOf(false) }
    ChatListContent(
        ui, nameOf = { p -> names[p.deviceId] ?: p.name }, notice = notice, modifier = modifier,
        onOpen = vm::open, onNewChat = { creating = true },
        onApprove = vm::approve, onDeny = vm::deny, onJoin = vm::join, onDecline = vm::decline, onDismissNotice = vm::dismissNotice,
    )
    if (creating) NewChatDialog(spaces, onCreate = { space, name, channel -> if (channel) vm.createChannel(space, name) else vm.createChat(space, name); creating = false }, onClose = { creating = false })
}

/** The list per the `PhoneChatList` mockup: approvals and invitations as cards, the chats grouped by space in 56 dp rows. */
@Composable
fun ChatListContent(
    ui: ChatListUi, nameOf: (Peer) -> String, notice: String?, modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
    onOpen: (String) -> Unit = {}, onNewChat: () -> Unit = {},
    onApprove: (ChatInvite) -> Unit = {}, onDeny: (ChatInvite) -> Unit = {},
    onJoin: (ChatInvite) -> Unit = {}, onDecline: (ChatInvite) -> Unit = {}, onDismissNotice: () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onNewChat, shape = ButtonShape, modifier = Modifier.heightIn(min = 44.dp)) { Text(Messages.NEW_CHAT_PHONE) }
        }
        notice?.let { NoticeBar(it, onDismissNotice) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            for (invite in ui.approvals) item(key = "approve-${invite.id}") { ApprovalCard(invite, nameOf, onApprove, onDeny) }
            for (invite in ui.invitations) item(key = "invite-${invite.id}") { InvitationCard(invite, onJoin, onDecline) }
            if (ui.groups.isEmpty() && ui.invitations.isEmpty() && ui.approvals.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No chats yet. Start one with ${Messages.NEW_CHAT_PHONE}.", color = Forge.Muted, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            for (group in ui.groups) {
                item(key = "space-${group.spaceName}") {
                    Text(
                        group.spaceName.uppercase(), color = Forge.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                    )
                }
                if (group.channels.isNotEmpty()) {
                    item(key = "channels-${group.spaceName}") { SectionLabel(Messages.CHANNELS) }
                    for (row in group.channels) item(key = row.chat.id) { ChatRowItem(row, now, onClick = { onOpen(row.chat.id) }) }
                }
                if (group.privateChats.isNotEmpty()) {
                    item(key = "private-${group.spaceName}") { SectionLabel(Messages.PRIVATE_CHATS) }
                    for (row in group.privateChats) item(key = row.chat.id) { ChatRowItem(row, now, onClick = { onOpen(row.chat.id) }) }
                }
            }
        }
    }
}

/** A space's "Channels" / "Private chats" subsection label; an empty subsection shows none. */
@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Forge.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp))
}

/** The title a chat row and header show: `# name` for an open channel, the name for a private chat. */
internal fun chatTitle(chat: ChatSummary): String = if (chat.kind == ChatKind.OPEN) "# ${chat.name}" else chat.name

@Composable
internal fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
    }
}

@Composable
private fun ChatRowItem(row: ChatRow, now: () -> Long, onClick: () -> Unit) {
    val unread = row.unread > 0
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(if (unread) AvatarUnread else AvatarIdle, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.chat.name.take(1).uppercase(), fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold,
                color = if (unread) Forge.Accent else Forge.Text,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(chatTitle(row.chat), Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!row.chat.member) {
                    Text(
                        Messages.ADMIN_VIEW, color = Forge.Muted, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.border(1.dp, Forge.Border, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
                    )
                }
                Box(Modifier.weight(1f))
                if (row.mention) Text("@", color = Forge.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "Unread mention" })
                if (row.chat.muted) Text(Messages.MUTED, color = Forge.Muted, fontSize = 11.sp, maxLines = 1)
                if (unread) {
                    Text(
                        row.unread.toString(), color = if (row.chat.muted) Forge.Text else Forge.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(if (row.chat.muted) MutedBadge else Forge.Accent, RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                } else row.lastAtEpochMs?.let { Text(listTime(it, now()), color = Forge.Muted, fontSize = 12.sp) }
            }
            Text(
                row.preview, color = if (unread) Forge.Text else Forge.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `{inviter} wants to add {name} to {chat}` for the owner, with *Approve* / *Deny*. */
@Composable
private fun ApprovalCard(invite: ChatInvite, nameOf: (Peer) -> String, onApprove: (ChatInvite) -> Unit, onDeny: (ChatInvite) -> Unit) {
    val inviter = nameOf(invite.inviter)
    val invitee = nameOf(invite.invitee)
    val text = buildAnnotatedString {
        append(Messages.inviteApproval(inviter, invitee) + " to ${invite.chatName}")
        val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
        addStyle(bold, 0, inviter.length)
        val nameAt = inviter.length + " wants to add ".length
        addStyle(bold, nameAt, nameAt + invitee.length)
    }
    InviteCard(text, Messages.APPROVE, Messages.DENY, { onApprove(invite) }, { onDeny(invite) })
}

/** `You're invited to {chat}` and its space, with *Join* / *Decline*. */
@Composable
private fun InvitationCard(invite: ChatInvite, onJoin: (ChatInvite) -> Unit, onDecline: (ChatInvite) -> Unit) {
    val text = buildAnnotatedString {
        val head = Messages.invitedTo(invite.chatName)
        append(head)
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), head.length - invite.chatName.length, head.length)
        append(" · ${invite.spaceName}")
    }
    InviteCard(text, Messages.JOIN, Messages.DECLINE, { onJoin(invite) }, { onDecline(invite) })
}

@Composable
private fun InviteCard(text: androidx.compose.ui.text.AnnotatedString, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp).fillMaxWidth().background(Forge.Surface, shape).border(1.dp, Forge.Accent, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onYes, shape = ButtonShape, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(yes, fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onNo, shape = ButtonShape, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(no) }
        }
    }
}

/** *New chat*: choose Chat or Channel, pick the space (when there are several) and name it (a channel has just a name). */
@Composable
fun NewChatDialog(spaces: List<Space>, onCreate: (Space, String, Boolean) -> Unit, onClose: () -> Unit) {
    var channel by rememberSaveable { mutableStateOf(false) }
    var pickedId by rememberSaveable { mutableStateOf(spaces.firstOrNull()?.id) }
    var name by rememberSaveable { mutableStateOf("") }
    val picked = spaces.firstOrNull { it.id == pickedId } ?: spaces.firstOrNull()
    val valid = picked != null && name.isNotBlank()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (channel) Messages.NEW_CHANNEL else Messages.NEW_CHAT_PHONE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (spaces.isEmpty()) {
                    Text("Chats live in a space. Join or create a space on the Devices tab first.", color = Forge.Muted)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for ((label, isChannel) in listOf("Chat" to false, "Channel" to true)) {
                            Row(
                                Modifier.heightIn(min = 48.dp).selectable(channel == isChannel, role = Role.RadioButton) { channel = isChannel },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(channel == isChannel, onClick = null)
                                Text(label, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    if (spaces.size > 1) {
                        Text("Space", color = Forge.Muted, style = MaterialTheme.typography.labelMedium)
                        for (space in spaces) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(space.id == picked?.id, role = Role.RadioButton) { pickedId = space.id },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(space.id == picked?.id, onClick = null)
                                Text(space.name, Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                    OutlinedTextField(
                        name, { name = it.take(Limits.MAX_SPACE_NAME_LENGTH) }, singleLine = true, shape = FieldShape, colors = forgeFieldColors(),
                        label = if (channel) ({ Text(Messages.CHANNEL_NAME) }) else null,
                        placeholder = { Text(if (channel) "games" else "Holiday planning", color = Forge.Muted) }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { picked?.let { onCreate(it, name.trim(), channel) } }, enabled = valid, modifier = Modifier.heightIn(min = 48.dp)) { Text("Create") } },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

/** [clock] re-read every minute, so "Last seen 3m ago" keeps up. */
@Composable
internal fun rememberMinuteClock(clock: () -> Long = System::currentTimeMillis): Long {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = clock() } }
    return now
}

/** The 8 dp presence dot (green online, grey otherwise) with "Online" or "Last seen …"; no text while unknown. */
@Composable
internal fun PresenceStatus(presence: Presence?, nowMs: Long, modifier: Modifier = Modifier) {
    val online = presence?.online == true
    val text = if (online) Messages.ONLINE else Messages.lastSeen(nowMs, presence?.lastSeenMs)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(if (online) OnlineDot else OfflineDot, CircleShape))
        if (text.isNotEmpty()) Text(text, color = Forge.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

/** The live presence of [peer], else what the peer itself carries. */
internal fun presenceOf(peer: Peer, live: Map<String, Presence>): Presence = live[peer.deviceId] ?: Presence(peer.online, peer.lastSeenMs)

/** The list's timestamp: the time today, the weekday within a week, else the date. */
internal fun listTime(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
    return when {
        day == today -> clockTime(ms, zone)
        day.isAfter(today.minusDays(7)) -> DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(day)
        else -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(day)
    }
}

internal fun clockTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone))

/** Another app shared something: "Send to a chat" lists the chats this phone can write in; *Throw instead* keeps the old way. */
@Composable
fun ShareToChatDialog(offer: ShareOffer, onPick: (chatId: String) -> Unit, onThrow: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel, containerColor = Forge.Surface,
        title = { Text("Send to a chat") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp).semantics { contentDescription = "Chats to send to" }) {
                items(offer.chats.size) { i ->
                    val chat = offer.chats[i]
                    Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onPick(chat.id) }.padding(horizontal = 4.dp, vertical = 6.dp)) {
                        Text(chatTitle(chat), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(chat.spaceName, color = Forge.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onThrow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Throw instead") } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}
