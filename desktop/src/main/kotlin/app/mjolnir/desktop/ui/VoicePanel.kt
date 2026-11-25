package app.mjolnir.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.Messages
import app.mjolnir.client.voice.ActiveVoice
import app.mjolnir.client.voice.VoiceState
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.VoiceRoom

/** What the chat list's voice parts show: the rooms of every space, and the room this device is in. */
class VoiceUi(
    /** False on a relay without voice rooms: nothing about voice shows. */
    val supported: Boolean = false,
    val spaces: List<Space> = emptyList(),
    val rooms: Map<String, List<VoiceRoom>> = emptyMap(),
    val active: ActiveVoice? = null,
    /** Why the last session ended (or a room action failed); shown until dismissed. */
    val notice: String? = null,
) {
    /** The room [active] is in, as last listed. */
    val activeRoom: VoiceRoom? get() = active?.let { a -> rooms.values.firstNotNullOfOrNull { list -> list.find { it.id == a.roomId } } }
}

class VoiceActions(
    val onJoin: (VoiceRoom) -> Unit = {},
    val onLeave: () -> Unit = {},
    val onMute: (Boolean) -> Unit = {},
    val onTalking: (Boolean) -> Unit = {},
    val onOpenMic: (Boolean) -> Unit = {},
    val onCreateRoom: (spaceId: String, name: String) -> Unit = { _, _ -> },
    val onRename: (VoiceRoom, String) -> Unit = { _, _ -> },
    val onDelete: (VoiceRoom) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * Hold-to-talk on the `` ` `` key: down starts talking, up stops. Returns whether the event was used; only in push to
 * talk (open mic leaves the key to typing). Key repeats while held are used but do not repeat the call.
 */
fun pushToTalkKey(e: KeyEvent, active: ActiveVoice?, onTalking: (Boolean) -> Unit): Boolean {
    if (e.key != Key.Grave || active == null || active.openMic) return false
    when (e.type) {
        // A text field with focus gets the key as a character; a release still ends talking that began before it took focus.
        KeyEventType.KeyDown -> if (TextInputFocus.any) return false else if (!active.talking) onTalking(true)
        KeyEventType.KeyUp -> if (TextInputFocus.any && !active.talking) return false else onTalking(false)
        else -> return false
    }
    return true
}

/** Which text inputs have focus right now: the push-to-talk key is typing, not talking, while one does. */
internal object TextInputFocus {
    private val focused = java.util.Collections.synchronizedSet(HashSet<Any>())
    val any: Boolean get() = focused.isNotEmpty()
    fun set(owner: Any, on: Boolean) { if (on) focused.add(owner) else focused.remove(owner) }
    fun clear() = focused.clear()
}

/** Marks a text input: while it has focus, `` ` `` types instead of talking. */
@Composable
internal fun Modifier.trackTextFocus(): Modifier {
    val owner = remember { Any() }
    androidx.compose.runtime.DisposableEffect(owner) { onDispose { TextInputFocus.set(owner, false) } }
    return this.onFocusChanged { TextInputFocus.set(owner, it.isFocused) }
}

/** The voice room rows of one space, under the `Voice` label, and the button that makes a new room. */
@Composable
internal fun VoiceRoomsSection(
    spaceId: String, ui: VoiceUi, nicknameOf: (Peer) -> String, actions: VoiceActions, onNewRoom: () -> Unit,
    canManage: (VoiceRoom) -> Boolean = { false }, onManage: (VoiceRoom) -> Unit = {},
) {
    Text(Messages.VOICE, style = voiceBody(12, ForgeFonts.inter600, Forge.muted), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp))
    for (room in ui.rooms[spaceId].orEmpty()) {
        val names = room.participants.joinToString(", ") { nicknameOf(it) }
        val inRoom = ui.active?.roomId == room.id
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).heightIn(min = 36.dp)
                    .background(if (inRoom) Forge.surface else androidx.compose.ui.graphics.Color.Transparent, Forge.rowShape)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = if (names.isEmpty()) room.name else "${room.name} — $names"
                    }
                    .clickable { actions.onJoin(room) }.padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("🔊 ${room.name}", style = voiceBody(14, if (inRoom) ForgeFonts.inter600 else ForgeFonts.inter400, if (inRoom) Forge.accent else Forge.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (names.isNotEmpty()) Text(names, style = voiceBody(12, color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (canManage(room)) Text(
                "⋯", style = voiceBody(16, color = Forge.muted),
                modifier = Modifier.clickable(role = Role.Button) { onManage(room) }.padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Room options: ${room.name}" },
            )
        }
    }
    Text(
        Messages.NEW_VOICE_ROOM, style = voiceBody(13, ForgeFonts.inter600, Forge.accent),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onNewRoom).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The room this device is in: who is there (a ring around who speaks), mute, leave, and how to talk. */
@Composable
internal fun VoicePanel(ui: VoiceUi, selfId: String?, nicknameOf: (Peer) -> String, actions: VoiceActions) {
    val active = ui.active ?: return
    Column(
        Modifier.fillMaxWidth().background(Forge.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("🔊 ${active.roomName}", style = voiceBody(14, ForgeFonts.inter600, Forge.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (active.state is VoiceState.Reconnecting) Text(Messages.RECONNECTING, style = voiceBody(12, color = Forge.muted))
        for (p in ui.activeRoom?.participants.orEmpty()) {
            val speaking = p.deviceId in active.speaking
            val muted = p.deviceId == selfId && active.muted
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .border(2.dp, if (speaking) Forge.accent else Forge.border, CircleShape)
                        .semantics { if (speaking) contentDescription = "Speaking" },
                )
                Text(nicknameOf(p), style = voiceBody(13), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (muted) Text("🔇", style = voiceBody(13), modifier = Modifier.semantics { contentDescription = "Muted" })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeSecondaryButton(if (active.muted) Messages.UNMUTE else Messages.MUTE, onClick = { actions.onMute(!active.muted) })
            ForgeSecondaryButton(Messages.LEAVE_VOICE, onClick = actions.onLeave)
        }
        Row(Modifier.fillMaxWidth().border(1.dp, Forge.border, Forge.buttonShape).clip(Forge.buttonShape)) {
            for ((label, open) in listOf(Messages.PUSH_TO_TALK to false, Messages.OPEN_MIC to true)) {
                val on = active.openMic == open
                Text(
                    label, style = voiceBody(13, if (on) ForgeFonts.inter600 else ForgeFonts.inter400, if (on) Forge.onAccent else Forge.text), textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).background(if (on) Forge.accent else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.RadioButton) { if (!on) actions.onOpenMic(open) }.padding(vertical = 7.dp),
                )
            }
        }
        if (active.openMic) {
            Text(Messages.USE_HEADPHONES, style = voiceBody(12, color = Forge.muted))
        } else {
            HoldToTalkButton(active.talking, actions.onTalking)
        }
    }
}

/** Talks while held (pointer down), stops on release or when the press is cancelled. */
@Composable
private fun HoldToTalkButton(talking: Boolean, onTalking: (Boolean) -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(Forge.buttonShape)
            .background(if (talking) Forge.accent else Forge.panel, Forge.buttonShape)
            .border(1.dp, if (talking) Forge.accent else Forge.border, Forge.buttonShape)
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onTalking(true)
                    try { tryAwaitRelease() } finally { onTalking(false) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(Messages.HOLD_TO_TALK, style = voiceBody(13, ForgeFonts.inter600, if (talking) Forge.onAccent else Forge.text))
    }
}

/** A voice room's options for its creator or the space admin: *Rename* and *Delete* (asked to confirm). */
@Composable
internal fun VoiceRoomDialog(room: VoiceRoom, actions: VoiceActions, onClose: () -> Unit) {
    var mode by remember { mutableStateOf("main") }
    var name by remember { mutableStateOf(room.name) }
    val valid = name.isNotBlank()
    ForgeModal(onDismiss = onClose) {
        Text("🔊 ${room.name}", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        when (mode) {
            "rename" -> {
                ForgeInput(name, { name = it.take(Limits.MAX_SPACE_NAME_LENGTH) }, modifier = Modifier.trackTextFocus(), placeholder = Messages.VOICE_ROOM_NAME, onEnter = { if (valid) { actions.onRename(room, name.trim()); onClose() } })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    ForgeSecondaryButton("Cancel", onClick = onClose)
                    ForgePrimaryButton("Save", enabled = valid, onClick = { actions.onRename(room, name.trim()); onClose() })
                }
            }
            "delete" -> {
                Text(Messages.deleteVoiceRoomConfirm(room.name), style = voiceBody(13, color = Forge.muted))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    ForgeSecondaryButton("Cancel", onClick = onClose)
                    ForgePrimaryButton("Delete", onClick = { actions.onDelete(room); onClose() })
                }
            }
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                ForgeSecondaryButton("Close", onClick = onClose)
                ForgeSecondaryButton("Rename", onClick = { mode = "rename" })
                ForgePrimaryButton("Delete", onClick = { mode = "delete" })
            }
        }
    }
}

/** The last voice failure or ending, with a way to dismiss it. */
@Composable
internal fun VoiceNotice(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = voiceBody(12, color = Forge.muted), modifier = Modifier.weight(1f))
        Text("×", style = voiceBody(16, color = Forge.muted), modifier = Modifier.clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 8.dp).semantics { contentDescription = "Dismiss" })
    }
}

/** *New voice room*: a name, then Create. */
@Composable
internal fun NewVoiceRoomDialog(spaceName: String, onCreate: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val valid = name.isNotBlank()
    ForgeModal(onDismiss = onClose) {
        Text(Messages.NEW_VOICE_ROOM, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text(spaceName, style = voiceBody(12, color = Forge.muted))
        ForgeInput(name, { name = it.take(Limits.MAX_SPACE_NAME_LENGTH) }, modifier = Modifier.trackTextFocus(), placeholder = Messages.VOICE_ROOM_NAME, onEnter = { if (valid) onCreate(name.trim()) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            ForgeSecondaryButton("Cancel", onClick = onClose)
            ForgePrimaryButton("Create", enabled = valid, onClick = { onCreate(name.trim()) })
        }
    }
}

private fun voiceBody(size: Int, family: androidx.compose.ui.text.font.FontFamily = ForgeFonts.inter400, color: androidx.compose.ui.graphics.Color = Forge.text) =
    androidx.compose.ui.text.TextStyle(fontFamily = family, fontSize = size.sp, color = color)
