package app.mjolnir.android.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.PairingCode
import app.mjolnir.protocol.Space
import kotlinx.coroutines.delay

/** Which dialog the Spaces screen opens as it appears (the Devices screen's *New space* and *Join a space*). */
enum class SpacesStart { NONE, NEW, JOIN }

/**
 * The spaces this phone is in, as full-width lists with 56 dp rows. The admin's space has *Invite*, *Rename* and
 * *Delete* and, per member, *Make admin* and *Remove*; a member's space has *Leave* and, once the admin has been
 * silent for 30 days, *Take over*. Every destructive action asks first.
 */
@Composable
fun SpacesScreen(vm: SpacesViewModel, start: SpacesStart, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    var creating by rememberSaveable { mutableStateOf(start == SpacesStart.NEW) }
    var joining by rememberSaveable { mutableStateOf(start == SpacesStart.JOIN) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    val activity = LocalActivity.current
    // A rotation keeps the invite code and any pending request; leaving the screen closes the code and stops listening.
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) vm.dismissInvite() } }

    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Spaces", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        }
        state.notice?.let { notice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(notice, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::dismissNotice, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }, shape = ButtonShape) { Text("New space") }
            Button(onClick = { vm.dismissJoin(); joining = true }, shape = ButtonShape) { Text("Join a space") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.spaces.isEmpty()) item { Text("You're not in any space yet.", color = Forge.Muted) }
            items(state.spaces, key = { it.id }) { space ->
                SpaceBlock(space, state, vm, onRename = { renamingId = space.id })
            }
        }
    }

    if (creating) NameDialog("New space", "", "Create", onCancel = { creating = false }) { vm.create(it); creating = false }
    renamingId?.let { id ->
        val space = state.spaces.find { it.id == id }
        if (space == null) renamingId = null
        else NameDialog("Rename space", space.name, "Save", onCancel = { renamingId = null }) { vm.rename(id, it); renamingId = null }
    }
    if (joining) JoinDialog(state.join, onJoin = vm::join, onClose = { vm.dismissJoin(); joining = false })
    state.confirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = vm::dismissConfirm,
            title = { Text(confirm.title) },
            confirmButton = { TextButton(onClick = vm::confirm) { Text(confirm.confirmLabel) } },
            dismissButton = { TextButton(onClick = vm::dismissConfirm) { Text("Cancel") } },
        )
    }
    state.request?.let { request ->
        AlertDialog(
            onDismissRequest = {}, // answer with a button, so a stray tap can't dismiss it
            title = { Text("${request.from.name} wants to join ${request.spaceName}") },
            confirmButton = { TextButton(onClick = { vm.answerRequest(true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { vm.answerRequest(false) }) { Text("Reject") } },
        )
    }
}

@Composable
private fun SpaceBlock(space: Space, state: SpacesUi, vm: SpacesViewModel, onRename: () -> Unit) {
    val isAdmin = space.adminId == state.selfId
    val invite = state.invite?.takeIf { it.spaceId == space.id }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ForgeCard {
            Column(Modifier.weight(1f)) {
                Text(space.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${space.members.size} ${if (space.members.size == 1) "device" else "devices"}${if (isAdmin) " · you're the admin" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = Forge.Muted,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isAdmin) {
                SpaceButton("Invite") { vm.invite(space.id) }
                SpaceButton("Rename", onClick = onRename)
                SpaceButton("Delete") { vm.askDelete(space) }
            } else {
                if (canTakeOver(space, System.currentTimeMillis())) SpaceButton("Take over") { vm.askTakeOver(space) }
                SpaceButton("Leave") { vm.askLeave(space) }
            }
        }
        if (invite != null) InvitePanel(invite, onNewCode = { vm.invite(space.id) }, onClose = vm::dismissInvite)
        for (member in space.members) {
            val admin = member.deviceId == space.adminId
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.nameOf(member), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val kind = if (member.kind == DeviceKind.PHONE) "Phone" else "PC"
                    Text(if (admin) "$kind · admin" else kind, style = MaterialTheme.typography.bodySmall, color = Forge.Muted)
                }
                if (isAdmin && member.deviceId != state.selfId) {
                    SpaceButton("Make admin") { vm.askMakeAdmin(space, member) }
                    SpaceButton("Remove") { vm.askRemove(space, member) }
                }
            }
        }
    }
}

@Composable
private fun SpaceButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

/** The admin's invite code with its countdown; once it runs out, *Get a new code*. */
@Composable
private fun InvitePanel(invite: InviteUi, onNewCode: () -> Unit, onClose: () -> Unit) {
    var remaining by remember(invite) { mutableLongStateOf(invite.expiresAtMs - System.currentTimeMillis()) }
    LaunchedEffect(invite) {
        while (true) {
            remaining = invite.expiresAtMs - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (remaining > 0) {
            Text(PairingCode.display(invite.code), color = Forge.Accent, fontSize = 40.sp, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("Expires in ${formatRemaining(remaining)}", color = Forge.Muted)
        } else {
            Text("The code expired.")
            Button(onClick = onNewCode, shape = ButtonShape) { Text("Get a new code") }
        }
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirmLabel: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val valid = text.isNotBlank()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                shape = FieldShape, colors = forgeFieldColors(), value = text,
                onValueChange = { text = it.take(Limits.MAX_SPACE_NAME_LENGTH) },
                placeholder = { Text("Friends") }, singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** *Join a space*: enter the invite code, then wait for the admin; the outcome replaces the form. */
@Composable
private fun JoinDialog(join: JoinUi, onJoin: (String) -> Unit, onClose: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (join is JoinUi.Joined) "Joined ${join.spaceName}" else "Join a space") },
        text = {
            when (join) {
                is JoinUi.Waiting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Waiting for ${join.adminName} to accept…")
                }
                is JoinUi.Joined -> Text("You can now throw to the devices in it.")
                else -> OutlinedTextField(
                    shape = FieldShape, colors = forgeFieldColors(), value = input, onValueChange = { input = it },
                    label = { Text("Code from the admin") }, placeholder = { Text("ABC-234") },
                    isError = join is JoinUi.Failed, supportingText = (join as? JoinUi.Failed)?.let { { Text(it.message) } },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            when (join) {
                is JoinUi.Waiting -> {}
                is JoinUi.Joined -> TextButton(onClick = onClose) { Text("Close") }
                else -> TextButton(onClick = { onJoin(input) }, enabled = input.isNotBlank()) { Text("Join") }
            }
        },
        dismissButton = { if (join !is JoinUi.Joined) TextButton(onClick = onClose) { Text(if (join is JoinUi.Waiting) "Close" else "Cancel") } },
    )
}
