package app.mjolnir.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mjolnir.client.Messages
import app.mjolnir.client.RelayException
import app.mjolnir.client.voice.ActiveVoice
import app.mjolnir.client.voice.VoiceController
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.VoiceRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the voice UI asks of the activity. */
sealed interface VoiceEffect {
    /** Ask for RECORD_AUDIO; answer with [VoiceViewModel.onMicPermission]. */
    data object RequestMicPermission : VoiceEffect
}

/** The microphone was refused: `Mjolnir needs the microphone for voice rooms.` with *Open settings*. */
data class MicPrompt(val text: String = Messages.MIC_PERMISSION, val action: String = Messages.OPEN_SETTINGS)

/** What the chat list's voice parts show: the rooms of every space, and the room this phone is in. */
data class PhoneVoiceUi(
    /** False on a relay without voice rooms: nothing about voice shows. */
    val supported: Boolean = false,
    val spaces: List<Space> = emptyList(),
    val rooms: Map<String, List<VoiceRoom>> = emptyMap(),
    val active: ActiveVoice? = null,
    /** Why the last session ended (or a room action failed); shown until dismissed. */
    val notice: String? = null,
    val micPrompt: MicPrompt? = null,
    val selfId: String? = null,
) {
    /** The room [active] is in, as last listed. */
    val activeRoom: VoiceRoom? get() = active?.let { a -> rooms.values.firstNotNullOfOrNull { list -> list.find { it.id == a.roomId } } }

    /** Rename and Delete: for the room's creator or the space admin. */
    fun canManage(room: VoiceRoom): Boolean =
        selfId != null && (room.creatorId == selfId || spaces.any { it.id == room.spaceId && it.adminId == selfId })
}

/**
 * The phone's voice rooms on screen. Joining needs the microphone: without the permission the activity is asked for it
 * first ([effects]); once granted the foreground service starts ([startService]) and then the room is joined, so Android
 * 14's rule for `microphone` services (permission granted, app in the foreground) holds.
 */
class VoiceViewModel(
    private val controller: VoiceController,
    private val selfId: () -> String?,
    private val hasMicPermission: () -> Boolean,
    private val startService: (VoiceRoom) -> Unit,
) : ViewModel() {
    private val spaces = MutableStateFlow<List<Space>>(emptyList())
    private val notice = MutableStateFlow<String?>(null)
    private val micPrompt = MutableStateFlow<MicPrompt?>(null)
    /** The room waiting for the permission answer. */
    private var pending: VoiceRoom? = null

    private val _effects = Channel<VoiceEffect>(Channel.BUFFERED)
    val effects: Flow<VoiceEffect> = _effects.receiveAsFlow()

    val ui: StateFlow<PhoneVoiceUi> = combine(
        combine(controller.supported, spaces, controller.rooms, ::Triple),
        controller.active, notice, micPrompt,
    ) { (supported, spaces, rooms), active, notice, prompt ->
        PhoneVoiceUi(supported, spaces, rooms, active, notice, prompt, selfId())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PhoneVoiceUi())

    init {
        viewModelScope.launch { controller.notices.collect { notice.value = it } }
    }

    /** The spaces were (re)loaded: their names and admins for the list, and their rooms fetched again. */
    fun spacesLoaded(all: List<Space>) {
        spaces.value = all
        viewModelScope.launch {
            try { controller.refresh(all) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* the next load or reconnect tries again */ }
        }
    }

    fun join(room: VoiceRoom) {
        notice.value = null
        micPrompt.value = null
        if (controller.active.value?.roomId == room.id) return
        if (!hasMicPermission()) {
            pending = room
            _effects.trySend(VoiceEffect.RequestMicPermission)
            return
        }
        start(room)
    }

    /** The answer to [VoiceEffect.RequestMicPermission]. */
    fun onMicPermission(granted: Boolean) {
        val room = pending ?: return
        pending = null
        if (granted) start(room) else micPrompt.value = MicPrompt()
    }

    private fun start(room: VoiceRoom) {
        startService(room)
        controller.join(room)
    }

    fun leave() = controller.leave()
    fun setMuted(muted: Boolean) = controller.setMuted(muted)
    fun setTalking(talking: Boolean) = controller.setTalking(talking)
    fun setOpenMic(openMic: Boolean) = controller.setOpenMic(openMic)

    fun createRoom(spaceId: String, name: String) = call { controller.createRoom(spaceId, name) }
    fun renameRoom(room: VoiceRoom, name: String) = call { controller.renameRoom(room.id, name) }
    fun deleteRoom(room: VoiceRoom) = call { controller.deleteRoom(room.id) }

    fun dismissNotice() { notice.value = null }
    fun dismissMicPrompt() { micPrompt.value = null }

    private fun call(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                notice.value = e.userMessage ?: Messages.chatError(e)
            }
        }
    }
}
