package app.mjolnir.android.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.Messages
import app.mjolnir.client.canInvite
import app.mjolnir.client.canLeave
import app.mjolnir.client.canManage
import app.mjolnir.client.canRemoveMembers
import app.mjolnir.client.canTransferOwner
import app.mjolnir.client.inviteCandidates
import app.mjolnir.protocol.ChatKind
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer

private val Danger = Color(0xFFF87171)

/** A destructive or far-reaching action waits for a yes. */
private class Confirm(val title: String, val label: String, val action: () -> Unit)

/**
 * The chat's settings (the phone twin of the desktop dialog): members with *Make owner* (the owner) and *Remove* (the
 * space admin), approvals waiting on the owner, *Invite…*, *Rename chat*, *Leave chat* and *Delete chat*. A sole owner
 * sees no *Leave chat* (deleting the chat is the way out); an owner with other members must hand over first.
 */
@Composable
fun ChatSettingsScreen(vm: ChatViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val chat by vm.chat.collectAsState()
    val members by vm.members.collectAsState()
    val spaces by vm.spaces.collectAsState()
    val invites by vm.list.collectAsState()
    val notice by vm.notice.collectAsState()
    val names by vm.nicknameMap.collectAsState()
    val current = chat
    LaunchedEffect(current == null) { if (current == null) onBack() } // the chat is gone (left, deleted, reset)
    if (current == null) return
    val self = vm.selfDeviceId
    val nameOf = { p: Peer -> names[p.deviceId] ?: p.name }
    val owner = current.ownerId == self
    val isAdmin = vm.isSpaceAdmin(current)
    val channel = current.kind == ChatKind.OPEN
    val canManage = canManage(current, self, isAdmin)
    val soleOwner = owner && members.isNotEmpty() && members.all { it.deviceId == self }
    val canLeave = canLeave(current) && !soleOwner
    val livePresence by vm.presence.collectAsState()
    val now = rememberMinuteClock()
    val pending = invites.approvals.filter { it.chatId == current.id }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(chatTitle(current), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        }
        Text(
            if (channel) Messages.channelHint(current.spaceName) else "${current.spaceName} · private chat · only members and the space admin can see it",
            color = Forge.Muted, fontSize = 12.sp,
        )
        notice?.let { NoticeBar(it, vm::dismissNotice) }
        Text("MEMBERS · ${members.size}", color = Forge.Muted, style = MaterialTheme.typography.labelMedium)
        for (member in members) {
            val me = member.deviceId == self
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(nameOf(member), fontWeight = if (me) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (member.deviceId == current.ownerId) Text("owner", color = Forge.Muted, fontSize = 12.sp)
                    PresenceStatus(presenceOf(member, livePresence), now)
                }
                if (canTransferOwner(current, self) && !me) TextButton(onClick = { vm.makeOwner(current.id, member.deviceId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(Messages.MAKE_OWNER) }
                if (canRemoveMembers(current, isAdmin) && !me) TextButton(
                    onClick = { confirm = Confirm("Remove ${nameOf(member)} from ${current.name}?", "Remove") { vm.removeMember(current.id, member.deviceId) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Remove", color = Forge.Muted) }
            }
        }
        if (pending.isNotEmpty()) {
            Text("PENDING", color = Forge.Muted, style = MaterialTheme.typography.labelMedium)
            for (invite in pending) PendingRow(invite, nameOf, onApprove = { vm.approve(invite) }, onDeny = { vm.deny(invite) })
        }
        if (canInvite(current)) {
            OutlinedButton(onClick = { inviting = true }, shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(Messages.INVITE) }
        }
        if (current.member) {
            OutlinedButton(
                onClick = { vm.setMuted(current.id, !current.muted) }, shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(if (current.muted) Messages.UNMUTE else Messages.MUTE) }
        }
        if (canManage) {
            OutlinedButton(onClick = { renaming = true }, shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(Messages.RENAME_CHAT) }
        }
        if (canLeave) {
            OutlinedButton(
                onClick = { confirm = Confirm("Leave ${current.name}?", Messages.LEAVE_CHAT) { vm.leave(current.id) } },
                enabled = !owner, shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(Messages.LEAVE_CHAT) }
            if (owner) Text("Make someone else the owner to leave.", color = Forge.Muted, fontSize = 12.sp)
        }
        if (canManage) {
            OutlinedButton(
                onClick = { confirm = Confirm(if (channel) Messages.deleteChannelConfirm(current.name, current.spaceName) else "Delete ${current.name}? Its messages go for everyone.", Messages.DELETE_CHAT) { vm.deleteChat(current.id) } },
                shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(Messages.DELETE_CHAT, color = Danger) }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null }, title = { Text(c.title) },
            confirmButton = { TextButton(onClick = { confirm = null; c.action() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(c.label) } },
            dismissButton = { TextButton(onClick = { confirm = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
    if (renaming) RenameDialog(current, onSave = { vm.rename(current.id, it); renaming = false }, onClose = { renaming = false })
    if (inviting) {
        val space = spaces.firstOrNull { it.id == current.spaceId }
        InviteDialog(
            current.name, current.spaceName, isOwner = owner, candidates = space?.let { inviteCandidates(it, members, self) }.orEmpty(), nameOf = nameOf,
            onInvite = { picked -> picked.forEach { vm.invite(current.id, it.deviceId) }; inviting = false }, onClose = { inviting = false },
        )
    }
}

@Composable
private fun PendingRow(invite: ChatInvite, nameOf: (Peer) -> String, onApprove: () -> Unit, onDeny: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Forge.Accent, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${nameOf(invite.invitee)} · invited by ${nameOf(invite.inviter)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApprove, shape = ButtonShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(Messages.APPROVE, fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onDeny, shape = ButtonShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(Messages.DENY) }
        }
    }
}

@Composable
private fun RenameDialog(chat: ChatSummary, onSave: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(chat.name) }
    val valid = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onClose, title = { Text(Messages.RENAME_CHAT) },
        text = {
            OutlinedTextField(
                name, { name = it.take(Limits.MAX_SPACE_NAME_LENGTH) }, singleLine = true, shape = FieldShape, colors = forgeFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = valid, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

/**
 * *Invite…*: the members of the chat's space who aren't in it yet, to pick from. An owner's invites need no approval
 * (the invitee just taps Join); anyone else's wait for the owner.
 */
@Composable
private fun InviteDialog(
    chatName: String, spaceName: String, isOwner: Boolean, candidates: List<Peer>, nameOf: (Peer) -> String,
    onInvite: (List<Peer>) -> Unit, onClose: () -> Unit,
) {
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Invite to $chatName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isOwner) "Members of $spaceName who aren't in this chat yet. As the owner, your invites need no approval: they just have to tap Join."
                    else "Members of $spaceName who aren't in this chat yet. The owner approves your invites before they can join.",
                    color = Forge.Muted, fontSize = 13.sp,
                )
                if (candidates.isEmpty()) Text("Everyone in $spaceName is already in this chat.")
                for (peer in candidates) {
                    val on = peer.deviceId in picked
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Checkbox) { if (on) picked.remove(peer.deviceId) else picked.add(peer.deviceId) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(on, onCheckedChange = null)
                        Text(nameOf(peer), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (peer.kind == DeviceKind.PHONE) "Phone" else "PC", color = Forge.Muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onInvite(candidates.filter { it.deviceId in picked }) }, enabled = picked.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Invite") }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}
