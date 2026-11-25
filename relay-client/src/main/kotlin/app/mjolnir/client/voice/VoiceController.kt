package app.mjolnir.client.voice

import app.mjolnir.client.Messages
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.VoiceRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The room this device is in and how its session stands. */
data class ActiveVoice(
    val roomId: String,
    val roomName: String,
    val state: VoiceState,
    /** Device ids speaking now, this device included. */
    val speaking: Set<String>,
    val muted: Boolean,
    val talking: Boolean,
    val openMic: Boolean,
)

/**
 * Voice rooms for the apps: the room list of every space, kept current from relay events, and the one [VoiceSession]
 * this device has open. [audio] opens the devices for each session; [selfId] is this device's id (for [ActiveVoice.speaking]).
 * Open mic starts as [openMicByDefault] (the phone) or push to talk (the PC), and [setOpenMic] carries over between rooms.
 */
class VoiceController(
    private val api: RelayApi,
    private val scope: CoroutineScope,
    private val audio: VoiceAudioFactory,
    private val selfId: () -> String? = { null },
    openMicByDefault: Boolean = false,
    /** Where each session's capture and playout loops run (they block on the audio devices). */
    private val audioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Paces each session's playout; one ticker per session. */
    private val newTicker: () -> VoiceTicker = { RealtimeTicker() },
) {
    private val _rooms = MutableStateFlow<Map<String, List<VoiceRoom>>>(emptyMap())
    /** Each space's voice rooms by space id, in the relay's order. */
    val rooms: StateFlow<Map<String, List<VoiceRoom>>> = _rooms.asStateFlow()

    private val _supported = MutableStateFlow(true)
    /** False on a relay without voice rooms: the apps show nothing for voice then. */
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    private val _active = MutableStateFlow<ActiveVoice?>(null)
    val active: StateFlow<ActiveVoice?> = _active.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One line when a session ended without the user leaving (connection lost, room full, kicked, ...). */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val lock = Any()
    private var session: VoiceSession? = null
    private var watcher: Job? = null
    @Volatile private var openMic = openMicByDefault
    @Volatile private var lastSpaces: List<Space> = emptyList()

    /** Fetches the rooms of [spaces]; a space whose fetch fails keeps the rooms it had. */
    suspend fun refresh(spaces: List<Space>) {
        lastSpaces = spaces
        val result = LinkedHashMap<String, List<VoiceRoom>>()
        for (space in spaces) {
            try {
                result[space.id] = api.voiceRooms(space.id)
            } catch (e: RelayException) {
                when (e.kind) {
                    RelayErrorKind.RELAY_OUTDATED -> {
                        _supported.value = false
                        _rooms.value = emptyMap()
                        return
                    }
                    RelayErrorKind.NOT_FOUND -> {} // no longer a member
                    else -> _rooms.value[space.id]?.let { result[space.id] = it }
                }
            }
        }
        _supported.value = true
        _rooms.value = result
    }

    fun onEvent(e: RelayEvent) {
        when (e) {
            is RelayEvent.VoiceRoomChanged -> upsert(e.room)
            is RelayEvent.VoiceRoomDeleted -> remove(e.roomId)
            is RelayEvent.Connected -> scope.launch {
                try {
                    refresh(lastSpaces)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            else -> {}
        }
    }

    suspend fun createRoom(spaceId: String, name: String): VoiceRoom = api.createVoiceRoom(spaceId, name).also(::upsert)

    suspend fun renameRoom(id: String, name: String): VoiceRoom = api.renameVoiceRoom(id, name).also(::upsert)

    suspend fun deleteRoom(id: String) {
        api.deleteVoiceRoom(id)
        remove(id)
    }

    /** Joins [room], leaving the room this device is in; joining the room it is already in does nothing. */
    fun join(room: VoiceRoom): Unit = synchronized(lock) {
        val current = session
        if (current != null && _active.value?.roomId == room.id && current.state.value !is VoiceState.Ended) return
        stop()
        val s = VoiceSession(
            { onRoster, onFrame -> api.openVoice(room.id, onRoster, onFrame) }, audio.input(), audio.output(), scope, selfId(),
            ticker = newTicker(), audioDispatcher = audioDispatcher,
        )
        s.openMic = openMic
        session = s
        _active.value = ActiveVoice(room.id, room.name, s.state.value, emptySet(), muted = false, talking = false, openMic = openMic)
        watcher = scope.launch {
            combine(s.state, s.speaking) { state, speaking -> state to speaking }.collect { (state, speaking) ->
                if (state is VoiceState.Ended) ended(s, state.reason)
                else synchronized(lock) { if (session === s) _active.update { it?.copy(state = state, speaking = speaking) } }
            }
        }
    }

    fun leave() = synchronized(lock) { stop() }

    fun setMuted(muted: Boolean) = synchronized(lock) {
        session?.setMuted(muted)
        _active.update { it?.copy(muted = muted) }
    }

    fun setTalking(talking: Boolean) = synchronized(lock) {
        session?.setTalking(talking)
        _active.update { it?.copy(talking = talking) }
    }

    fun setOpenMic(openMic: Boolean) = synchronized(lock) {
        this.openMic = openMic
        session?.openMic = openMic
        _active.update { it?.copy(openMic = openMic) }
    }

    /** Leaves the current session, if any. Under [lock]. */
    private fun stop() {
        session?.leave()
        watcher?.cancel()
        session = null
        watcher = null
        _active.value = null
    }

    private fun ended(s: VoiceSession, reason: String?) {
        val notice = synchronized(lock) {
            if (session !== s) return
            val left = _active.value
            session = null
            _active.value = null
            watcher?.cancel()
            watcher = null
            left?.let {
                // The room as last listed: its count and the relay's limit (unknown: Messages falls back to 8, never 0/0).
                val room = roomById(it.roomId)
                Messages.voiceEnded(reason, it.roomName, room?.participants?.size ?: 0, room?.maxParticipants ?: 0)
            }
        }
        notice?.let { _notices.tryEmit(it) }
    }

    private fun roomById(id: String): VoiceRoom? = _rooms.value.values.firstNotNullOfOrNull { list -> list.find { it.id == id } }

    private fun upsert(room: VoiceRoom) {
        _rooms.update { map ->
            val list = map[room.spaceId].orEmpty()
            val index = list.indexOfFirst { it.id == room.id }
            map + (room.spaceId to if (index >= 0) list.toMutableList().also { it[index] = room } else list + room)
        }
        _active.update { if (it?.roomId == room.id) it.copy(roomName = room.name) else it }
    }

    private fun remove(roomId: String) {
        _rooms.update { map -> map.mapValues { (_, list) -> list.filter { it.id != roomId } } }
    }
}
