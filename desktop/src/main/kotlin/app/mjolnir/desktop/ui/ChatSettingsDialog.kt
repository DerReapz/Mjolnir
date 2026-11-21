package app.mjolnir.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.Messages
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.InviteState
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer

/** What the chat settings can ask for; the Chats tab wires these to the controller. */
class ChatSettingsActions(
    val onRename: (String) -> Unit = {},
    val onMakeOwner: (Peer) -> Unit = {},
    val onRemove: (Peer) -> Unit = {},
    val onLeave: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onApprove: (ChatInvite) -> Unit = {},
    val onDeny: (ChatInvite) -> Unit = {},
)

/**
 * What the settings dialog shows. [invites] are this device's actionable invites (only [ChatInvite.chatId] matches count);
 * [isSpaceAdmin] is whether this device administers the chat's space.
 */
class ChatSettings(
    val chat: ChatSummary,
    val members: List<Peer>,
    val invites: List<ChatInvite>,
    val selfId: String?,
    val isSpaceAdmin: Boolean,
    val nicknameOf: (Peer) -> String,
    val actions: ChatSettingsActions,
)

private sealed interface Mode {
    data object Main : Mode
    data object Renaming : Mode
    data class Confirm(val title: String, val label: String, val action: () -> Unit) : Mode
}

private val DANGER_INK = Color(0xFFF87171)
private fun small(size: Int, color: Color = Forge.text, family: androidx.compose.ui.text.font.FontFamily = ForgeFonts.inter400) =
    TextStyle(fontFamily = family, fontSize = size.sp, color = color)

/**
 * The chat's settings (per the `DesktopChatInvite` mockup): members with *Make owner* (the owner) and *Remove* (the space
 * admin), approvals waiting on the owner, *Rename chat*, *Leave chat* and *Delete chat*. A sole owner who is the last
 * member sees no *Leave chat* (ruling CC-R5): deleting the chat is the way out. Leaving and deleting ask first.
 */
@Composable
fun ChatSettingsDialog(s: ChatSettings, onClose: () -> Unit) {
    val chat = s.chat
    var mode by remember { mutableStateOf<Mode>(Mode.Main) }
    val owner = chat.ownerId == s.selfId
    val canDelete = owner || s.isSpaceAdmin
    val soleOwner = owner && s.members.all { it.deviceId == s.selfId }
    val canLeave = chat.member && !soleOwner
    ForgeModal(onDismiss = onClose, maxWidth = 460.dp) {
        when (val m = mode) {
            is Mode.Confirm -> {
                Text(m.title, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    ForgeSecondaryButton("Cancel", onClick = { mode = Mode.Main })
                    ForgePrimaryButton(m.label, onClick = { m.action(); onClose() })
                }
            }
            Mode.Renaming -> {
                var name by remember { mutableStateOf(chat.name) }
                val valid = name.isNotBlank()
                Text(Messages.RENAME_CHAT, style = MaterialTheme.typography.titleMedium)
                ForgeInput(name, { name = it.take(Limits.MAX_SPACE_NAME_LENGTH) }, onEnter = { if (valid) { s.actions.onRename(name.trim()); onClose() } })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    ForgeSecondaryButton("Cancel", onClick = { mode = Mode.Main })
                    ForgePrimaryButton("Save", enabled = valid, onClick = { s.actions.onRename(name.trim()); onClose() })
                }
            }
            Mode.Main -> Main(s, owner, canLeave, canDelete, soleOwner, onClose, setMode = { mode = it })
        }
    }
}

@Composable
private fun Main(s: ChatSettings, owner: Boolean, canLeave: Boolean, canDelete: Boolean, soleOwner: Boolean, onClose: () -> Unit, setMode: (Mode) -> Unit) {
    val chat = s.chat
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(chat.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ForgeTextButton("Close", muted = true, onClick = onClose)
    }
    Text("${chat.spaceName} · private chat · only members and the space admin can see it", style = small(12, Forge.muted))
    ForgeSectionLabel("Members · ${s.members.size}")
    Column {
        for ((i, member) in s.members.withIndex()) {
            val self = member.deviceId == s.selfId
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .drawBehind { if (i < s.members.lastIndex) drawLine(Forge.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildAnnotatedString {
                        append(s.nicknameOf(member))
                        if (member.deviceId == chat.ownerId) withStyleMuted { append(" · owner") }
                    },
                    Modifier.weight(1f), style = small(14, family = if (self) ForgeFonts.inter600 else ForgeFonts.inter400), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (owner && !self) ForgeTextButton(Messages.MAKE_OWNER, onClick = { s.actions.onMakeOwner(member) })
                if (s.isSpaceAdmin && !self) ForgeTextButton("Remove", muted = true, onClick = {
                    setMode(Mode.Confirm("Remove ${s.nicknameOf(member)} from ${chat.name}?", "Remove") { s.actions.onRemove(member) })
                })
            }
        }
    }
    val pending = s.invites.filter { it.state == InviteState.PENDING_APPROVAL }
    if (pending.isNotEmpty()) {
        ForgeSectionLabel("Pending")
        for (invite in pending) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).border(1.dp, Forge.accent, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${s.nicknameOf(invite.invitee)} · invited by ${s.nicknameOf(invite.inviter)}", Modifier.weight(1f), style = small(13))
                ChatButton(Messages.APPROVE, { s.actions.onApprove(invite) }, primary = true)
                ChatButton(Messages.DENY, { s.actions.onDeny(invite) })
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (owner || s.isSpaceAdmin) ChatButton(Messages.RENAME_CHAT, { setMode(Mode.Renaming) }, tall = true)
        if (chat.member && !soleOwner) {
            ChatButton(Messages.LEAVE_CHAT, { setMode(Mode.Confirm("Leave ${chat.name}?", Messages.LEAVE_CHAT) { s.actions.onLeave() }) }, enabled = !owner, tall = true)
        }
        Box(Modifier.weight(1f))
        if (canDelete) ChatButton(Messages.DELETE_CHAT, {
            setMode(Mode.Confirm("Delete ${chat.name}? Its messages go for everyone.", Messages.DELETE_CHAT) { s.actions.onDelete() })
        }, danger = true, tall = true)
    }
    if (owner && !soleOwner && canLeave) Text("Make someone else the owner to leave.", style = small(12, Forge.muted))
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleMuted(block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit) {
    val start = length
    block()
    addStyle(SpanStyle(color = Forge.muted, fontSize = 12.sp), start, length)
}

/**
 * *Invite…*: the members of the chat's space who aren't in it yet, to pick from. An owner's invites need no approval
 * (the invitee just taps Join); anyone else's wait for the owner.
 */
@Composable
fun ChatInviteDialog(
    chatName: String, spaceName: String, isOwner: Boolean, candidates: List<Peer>, nicknameOf: (Peer) -> String,
    onInvite: (List<Peer>) -> Unit, onClose: () -> Unit,
) {
    val picked = remember { mutableStateListOf<String>() }
    ForgeModal(onDismiss = onClose) {
        Text("Invite to $chatName", style = MaterialTheme.typography.titleMedium)
        Text(
            if (isOwner) "Members of $spaceName who aren't in this chat yet. As the owner, your invites need no approval — they just have to tap Join."
            else "Members of $spaceName who aren't in this chat yet. The owner approves your invites before they can join.",
            style = small(13, Forge.muted),
        )
        if (candidates.isEmpty()) Text("Everyone in $spaceName is already in this chat.", style = small(13))
        for (peer in candidates) {
            val on = peer.deviceId in picked
            fun toggle() { if (on) picked.remove(peer.deviceId) else picked.add(peer.deviceId) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Forge.panel, Forge.inputShape).clickable { toggle() }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Checkbox(on, { toggle() }, colors = forgeCheckboxColors())
                Text(nicknameOf(peer), Modifier.weight(1f), style = small(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kindLabel(peer), style = small(12, Forge.muted))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            ForgeSecondaryButton("Cancel", onClick = onClose)
            ForgePrimaryButton("Invite", enabled = picked.isNotEmpty(), onClick = { onInvite(candidates.filter { it.deviceId in picked }) })
        }
    }
}
