package app.mjolnir.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.desktop.InviteUi
import app.mjolnir.desktop.JoinUi
import app.mjolnir.client.canTakeOver
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.PairingCode
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import kotlinx.coroutines.delay

/** What the Spaces section can ask for; the Devices tab wires these to the [app.mjolnir.desktop.SpacesController]. */
class SpacesActions(
    val onCreate: (String) -> Unit = {},
    val onJoin: (String) -> Unit = {},
    val onInvite: (Space) -> Unit = {},
    val onDismissInvite: () -> Unit = {},
    val onRemove: (Space, Peer) -> Unit = { _, _ -> },
    val onLeave: (Space) -> Unit = {},
    val onRename: (Space, String) -> Unit = { _, _ -> },
    val onDelete: (Space) -> Unit = {},
    val onMakeAdmin: (Space, Peer) -> Unit = { _, _ -> },
    val onTakeOver: (Space) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onDismissJoin: () -> Unit = {},
)

/** A dialog the Spaces section opens over the Devices tab. */
sealed interface SpaceDialog {
    data object New : SpaceDialog
    data object Join : SpaceDialog
    data class Rename(val space: Space) : SpaceDialog
    /** A destructive or far-reaching action waits for a yes: [title] asks, [confirmLabel] is the button that does it. */
    data class Confirm(val title: String, val confirmLabel: String, val action: () -> Unit) : SpaceDialog
}

private val MAX_SPACE_NAME = Limits.MAX_SPACE_NAME_LENGTH
private val nameStyle = TextStyle(fontFamily = ForgeFonts.inter600, fontSize = 14.sp, color = Forge.text)

/**
 * The *Spaces* section of the Devices tab: *New space…* and *Join a space*, then one card per space. The admin's card
 * has *Invite…*, *Rename space*, *Delete space* and per member *Remove* and *Make admin*; a member's card has
 * *Leave space* and, once the admin has been silent for 30 days, *Take over as admin*. [clock] says what time it is.
 */
@Composable
fun SpacesSection(
    spaces: List<Space>,
    selfId: String?,
    nicknameOf: (Peer) -> String,
    invite: InviteUi?,
    notice: String?,
    onDialog: (SpaceDialog) -> Unit,
    actions: SpacesActions,
    clock: () -> Long = System::currentTimeMillis,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeSectionLabel("Spaces")
            Spacer(Modifier.weight(1f))
            ForgeSecondaryButton("New space…", onClick = { onDialog(SpaceDialog.New) })
            ForgeSecondaryButton("Join a space", onClick = { onDialog(SpaceDialog.Join) })
        }
        notice?.let {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.error))
                ForgeTextButton("Close", muted = true, onClick = actions.onDismissNotice)
            }
        }
        for (space in spaces) {
            SpaceCard(space, isAdmin = space.adminId == selfId, selfId, nicknameOf, invite?.takeIf { it.spaceId == space.id }, onDialog, actions, clock)
        }
    }
}

@Composable
private fun SpaceCard(
    space: Space, isAdmin: Boolean, selfId: String?, nicknameOf: (Peer) -> String, invite: InviteUi?,
    onDialog: (SpaceDialog) -> Unit, actions: SpacesActions, clock: () -> Long,
) {
    fun confirm(title: String, label: String, action: () -> Unit) = onDialog(SpaceDialog.Confirm(title, label, action))
    Column(Modifier.fillMaxWidth().forgeCard(selected = false).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(space.name, Modifier.weight(1f), style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isAdmin) {
                ForgeSecondaryButton("Invite…", onClick = { actions.onInvite(space) })
                ForgeSecondaryButton("Rename space", onClick = { onDialog(SpaceDialog.Rename(space)) })
                ForgeSecondaryButton("Delete space", onClick = { confirm("Delete ${space.name}?", "Delete space") { actions.onDelete(space) } })
            } else {
                if (canTakeOver(space, clock())) {
                    ForgeSecondaryButton("Take over as admin", onClick = { confirm("Take over ${space.name}?", "Take over as admin") { actions.onTakeOver(space) } })
                }
                ForgeSecondaryButton("Leave space", onClick = { confirm("Leave ${space.name}?", "Leave space") { actions.onLeave(space) } })
            }
        }
        if (invite != null) InvitePanel(invite, onNewCode = { actions.onInvite(space) }, onClose = actions.onDismissInvite, clock)
        for (member in space.members) {
            val admin = member.deviceId == space.adminId
            val self = member.deviceId == selfId
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(nicknameOf(member), style = TextStyle(fontFamily = ForgeFonts.inter500, fontSize = 14.sp, color = Forge.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val kind = if (member.kind == DeviceKind.PHONE) "Phone" else "PC"
                    Text(if (admin) "$kind · admin" else kind, style = MaterialTheme.typography.bodySmall.copy(color = Forge.muted))
                }
                if (isAdmin && !self) {
                    ForgeTextButton("Make admin", onClick = {
                        confirm("Make ${nicknameOf(member)} admin of ${space.name}?", "Make admin") { actions.onMakeAdmin(space, member) }
                    })
                    ForgeTextButton("Remove", muted = true, onClick = {
                        confirm("Remove ${nicknameOf(member)} from ${space.name}?", "Remove") { actions.onRemove(space, member) }
                    })
                }
            }
        }
    }
}

/** The admin's invite code with its countdown; once it runs out, *Get a new code*. */
@Composable
private fun InvitePanel(invite: InviteUi, onNewCode: () -> Unit, onClose: () -> Unit, clock: () -> Long) {
    var remaining by remember(invite) { mutableLongStateOf(invite.expiresAtMs - clock()) }
    LaunchedEffect(invite) {
        while (true) {
            remaining = invite.expiresAtMs - clock()
            if (remaining <= 0) break
            delay(250)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (remaining > 0) {
            ForgeCode(PairingCode.display(invite.code))
            ForgeBodyText("Expires in ${formatRemaining(remaining)}", muted = true, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
            ForgePrimaryButton("Get a new code", onClick = onNewCode)
        }
        ForgeTextButton("Close", muted = true, onClick = onClose)
    }
}

/** The dialog for [dialog], drawn over its parent. [join] is the live state of the join flow. */
@Composable
fun SpaceDialogHost(dialog: SpaceDialog?, join: JoinUi, actions: SpacesActions, onClose: () -> Unit) {
    when (dialog) {
        null -> {}
        SpaceDialog.New -> NameDialog("New space", "", "Create", onClose) { actions.onCreate(it); onClose() }
        is SpaceDialog.Rename -> NameDialog("Rename space", dialog.space.name, "Save", onClose) { actions.onRename(dialog.space, it); onClose() }
        is SpaceDialog.Confirm -> ForgeModal(onDismiss = onClose) {
            Text(dialog.title, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                ForgeSecondaryButton("Cancel", onClick = onClose)
                ForgePrimaryButton(dialog.confirmLabel, onClick = { dialog.action(); onClose() })
            }
        }
        SpaceDialog.Join -> JoinDialog(join, actions, onClose)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirmLabel: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val valid = text.isNotBlank()
    ForgeModal(onDismiss = onCancel) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        ForgeInput(text, { text = it.take(MAX_SPACE_NAME) }, placeholder = "Friends", onEnter = { if (valid) onConfirm(text.trim()) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            ForgeSecondaryButton("Cancel", onClick = onCancel)
            ForgePrimaryButton(confirmLabel, enabled = valid, onClick = { onConfirm(text.trim()) })
        }
    }
}

/** *Join a space*: enter the invite code, then wait for the admin; the outcome replaces the form. */
@Composable
private fun JoinDialog(join: JoinUi, actions: SpacesActions, onClose: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val close = { actions.onDismissJoin(); onClose() }
    ForgeModal(onDismiss = close) {
        Text("Join a space", style = MaterialTheme.typography.titleMedium)
        when (join) {
            is JoinUi.Waiting -> {
                Text("Waiting for ${join.adminName} to accept…")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ForgeSecondaryButton("Close", onClick = close) }
            }
            is JoinUi.Joined -> {
                Text("Joined ${join.spaceName}", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ForgePrimaryButton("Close", onClick = close) }
            }
            else -> {
                Text("Code from the admin", style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted))
                ForgeInput(input, { input = it }, Modifier.width(260.dp), placeholder = "ABC-234", onEnter = { if (input.isNotBlank()) actions.onJoin(input) })
                (join as? JoinUi.Failed)?.let { Text(it.message, style = TextStyle(fontSize = 13.sp, color = Forge.error)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    ForgeSecondaryButton("Cancel", onClick = close)
                    ForgePrimaryButton("Join", enabled = input.isNotBlank(), onClick = { actions.onJoin(input) })
                }
            }
        }
    }
}
