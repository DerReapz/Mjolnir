package app.mjolnir.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.Messages
import app.mjolnir.client.voice.VoiceState
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.VoiceRoom

/** What the voice parts of the chat list do. */
class PhoneVoiceActions(
    val onJoin: (VoiceRoom) -> Unit = {},
    val onLeave: () -> Unit = {},
    val onMute: (Boolean) -> Unit = {},
    val onTalking: (Boolean) -> Unit = {},
    val onOpenMic: (Boolean) -> Unit = {},
    val onNewRoom: (spaceId: String) -> Unit = {},
    val onManage: (VoiceRoom) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onDismissMicPrompt: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/** A space's voice rooms under the `Voice` label (`🔊 {room}` and who is in it), with *New voice room* below. */
@Composable
internal fun VoiceRoomsSection(spaceId: String, voice: PhoneVoiceUi, nameOf: (Peer) -> String, actions: PhoneVoiceActions) {
    Column {
        Text(Messages.VOICE, color = Forge.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp))
        for (room in voice.rooms[spaceId].orEmpty()) {
            val names = room.participants.joinToString(", ") { nameOf(it) }
            val inRoom = voice.active?.roomId == room.id
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .background(if (inRoom) Forge.Surface else Color.Transparent)
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = if (names.isEmpty()) "${Messages.JOIN_VOICE}: ${room.name}" else "${Messages.JOIN_VOICE}: ${room.name} — $names"
                        }
                        .clickable { actions.onJoin(room) }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "🔊 ${room.name}", color = if (inRoom) Forge.Accent else Forge.Text,
                        fontWeight = if (inRoom) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (names.isNotEmpty()) Text(names, color = Forge.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (voice.canManage(room)) {
                    Box(
                        Modifier.size(48.dp).clickable(role = Role.Button) { actions.onManage(room) }
                            .semantics { contentDescription = "Room options: ${room.name}" },
                        contentAlignment = Alignment.Center,
                    ) { Text("⋯", color = Forge.Muted, fontSize = 18.sp) }
                }
            }
        }
        TextButton(onClick = { actions.onNewRoom(spaceId) }, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
            Text(Messages.NEW_VOICE_ROOM, color = Forge.Accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The room this phone is in: who is there (a ring around who speaks), Mute, Leave, and how to talk. */
@Composable
internal fun VoicePanel(voice: PhoneVoiceUi, nameOf: (Peer) -> String, actions: PhoneVoiceActions) {
    val active = voice.active ?: return
    Column(
        Modifier.fillMaxWidth().background(Forge.Surface).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("🔊 ${active.roomName}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (active.state is VoiceState.Reconnecting) Text(Messages.RECONNECTING, color = Forge.Muted, fontSize = 13.sp)
        for (p in voice.activeRoom?.participants.orEmpty()) {
            val speaking = p.deviceId in active.speaking
            val muted = p.deviceId == voice.selfId && active.muted
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(16.dp).clip(CircleShape).border(2.dp, if (speaking) Forge.Accent else Forge.Border, CircleShape)
                        .semantics { if (speaking) contentDescription = "Speaking" },
                )
                Text(nameOf(p), Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (muted) Text("🔇", Modifier.semantics { contentDescription = "Muted" })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.onMute(!active.muted) }, shape = ButtonShape, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Text(if (active.muted) Messages.UNMUTE else Messages.MUTE)
            }
            Button(onClick = actions.onLeave, shape = ButtonShape, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Text(Messages.LEAVE_VOICE, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth().border(1.dp, Forge.Border, ButtonShape).clip(ButtonShape)) {
            for ((label, open) in listOf(Messages.PUSH_TO_TALK to false, Messages.OPEN_MIC to true)) {
                val on = active.openMic == open
                Text(
                    label, color = if (on) Forge.OnAccent else Forge.Text, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center, fontSize = 14.sp,
                    modifier = Modifier.weight(1f).background(if (on) Forge.Accent else Color.Transparent)
                        .clickable(role = Role.RadioButton) { if (!on) actions.onOpenMic(open) }.padding(vertical = 12.dp),
                )
            }
        }
        if (active.openMic) Text(Messages.USE_HEADPHONES, color = Forge.Muted, fontSize = 13.sp)
        else HoldToTalkButton(active.talking, actions.onTalking)
    }
}

/** Talks while held, stops on release or when the press is cancelled. */
@Composable
private fun HoldToTalkButton(talking: Boolean, onTalking: (Boolean) -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(ButtonShape)
            .background(if (talking) Forge.Accent else Forge.SurfaceAlt, ButtonShape)
            .border(1.dp, if (talking) Forge.Accent else Forge.Border, ButtonShape)
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onTalking(true)
                    try { tryAwaitRelease() } finally { onTalking(false) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(Messages.PUSH_TO_TALK, color = if (talking) Forge.OnAccent else Forge.Text, fontWeight = FontWeight.SemiBold)
    }
}

/** The microphone was refused: the sentence and *Open settings*. */
@Composable
internal fun MicPromptBar(prompt: MicPrompt, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(prompt.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(prompt.action) }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        }
    }
}

/** On the other tabs: `In voice: {room}`; tapping it opens the chat list with the voice panel. */
@Composable
fun InVoiceBar(room: String, onOpen: () -> Unit) {
    Text(
        Messages.inVoice(room), color = Forge.OnAccent, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(Forge.Accent).clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** *New voice room*: a name, then Create. */
@Composable
internal fun NewVoiceRoomDialog(spaceName: String, onCreate: (String) -> Unit, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(Messages.NEW_VOICE_ROOM) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(spaceName, color = Forge.Muted)
                VoiceRoomNameField(name) { name = it }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Create") } },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

/** A voice room's options for its creator or the space admin: Rename, and Delete (asked to confirm). */
@Composable
internal fun VoiceRoomDialog(room: VoiceRoom, onRename: (String) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf("main") }
    var name by rememberSaveable { mutableStateOf(room.name) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("🔊 ${room.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = when (mode) {
            "rename" -> ({ VoiceRoomNameField(name) { name = it } })
            "delete" -> ({ Text(Messages.deleteVoiceRoomConfirm(room.name)) })
            else -> null
        },
        confirmButton = {
            when (mode) {
                "rename" -> TextButton(onClick = { onRename(name.trim()); onClose() }, enabled = name.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
                "delete" -> TextButton(onClick = { onDelete(); onClose() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                else -> Row {
                    TextButton(onClick = { mode = "rename" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Rename") }
                    TextButton(onClick = { mode = "delete" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (mode == "main") "Close" else "Cancel") } },
    )
}

@Composable
private fun VoiceRoomNameField(name: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        name, { onChange(it.take(Limits.MAX_SPACE_NAME_LENGTH)) }, singleLine = true, shape = FieldShape, colors = forgeFieldColors(),
        label = { Text(Messages.VOICE_ROOM_NAME) }, modifier = Modifier.fillMaxWidth(),
    )
}
