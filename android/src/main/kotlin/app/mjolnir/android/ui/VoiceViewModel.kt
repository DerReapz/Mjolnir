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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    /** Starts the foreground service; false when Android refused it. */
    private val startService: (VoiceRoom) -> Boolean,
    /** Why the service ended a session (it could not run in the foreground). */
    private val serviceNotices: Flow<String> = emptyFlow(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val spaces = MutableStateFlow<List<Space>>(emptyList())
    /** This phone's device id, read from disk off the main thread (at start and with each spaces load), never per update. */
    private val self = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val micPrompt = MutableStateFlow<MicPrompt?>(null)
    /** The room waiting for the permission answer. */
    private var pending: VoiceRoom? = null

    private val _effects = Channel<VoiceEffect>(Channel.BUFFERED)
    val effects: Flow<VoiceEffect> = _effects.receiveAsFlow()

    val ui: StateFlow<PhoneVoiceUi> = combine(
        combine(controller.supported, spaces, controller.rooms, ::Triple),
        controller.active, notice, micPrompt, self,
    ) { (supported, spaces, rooms), active, notice, prompt, self ->
        PhoneVoiceUi(supported, spaces, rooms, active, notice, prompt, self)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PhoneVoiceUi())

    init {
        viewModelScope.launch { controller.notices.collect { notice.value = it } }
        viewModelScope.launch { serviceNotices.collect { notice.value = it } }
        readSelf()
    }

    /** The spaces were (re)loaded: their names and admins for the list, and their rooms fetched again. */
    fun spacesLoaded(all: List<Space>) {
        spaces.value = all
        readSelf() // a re-registration changes it
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

    private fun readSelf() {
        viewModelScope.launch(io) { self.value = try { selfId() } catch (e: CancellationException) { throw e } catch (_: Exception) { self.value } }
    }

    /** Never voice without the foreground service: if Android refuses it, the room is not joined. */
    private fun start(room: VoiceRoom) {
        if (startService(room)) controller.join(room) else notice.value = Messages.VOICE_AUDIO_ERROR
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

/** "No chats yet": only when there is nothing else to show, voice rooms included. */
internal fun showNoChatsHint(ui: ChatListUi, voice: PhoneVoiceUi): Boolean =
    ui.groups.isEmpty() && ui.invitations.isEmpty() && ui.approvals.isEmpty() && (!voice.supported || voice.rooms.values.all { it.isEmpty() })
