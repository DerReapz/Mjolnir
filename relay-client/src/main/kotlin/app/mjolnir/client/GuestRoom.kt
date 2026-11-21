package app.mjolnir.client

import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface GuestRoomUi {
    data object Closed : GuestRoomUi
    data object Opening : GuestRoomUi
    /**
     * [expiresAtEpochMs] is on the device's clock (see [GuestRoomController]); [guest] is null until someone joins.
     * [link] is empty for a room found already open after a restart: the relay keeps only a hash of the secret.
     */
    data class Open(val roomId: String, val link: String, val expiresAtEpochMs: Long, val guest: Peer?) : GuestRoomUi
    data class Failed(val message: String) : GuestRoomUi
}

/** One thing sent to or received from the guest, for the room's exchange list. [id] is the file id. */
data class Exchanged(
    val id: String,
    val fromGuest: Boolean,
    val title: String,
    val detail: String,
    val progress: Float?,
    val downloadedByGuest: Boolean,
    val localPath: String?,
)

/**
 * The owner's side of a guest room, shared by desktop and Android. The countdown is local: the expiry is always
 * `clock()` plus the time the relay says is left, so a wrong device clock doesn't matter.
 */
class GuestRoomController(
    private val api: () -> RelayApi?,
    private val relayUrl: () -> String,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow<GuestRoomUi>(GuestRoomUi.Closed)
    val state: StateFlow<GuestRoomUi> = _state.asStateFlow()

    private val _exchanged = MutableStateFlow<List<Exchanged>>(emptyList())
    val exchanged: StateFlow<List<Exchanged>> = _exchanged.asStateFlow()

    private fun link(secret: String) = "${relayUrl().trimEnd('/')}/g/$secret"

    /** Opens a room; does nothing while one is opening or open. */
    fun open() {
        if (_state.value is GuestRoomUi.Opening || _state.value is GuestRoomUi.Open) return
        val api = api() ?: return fail("Not connected to the relay")
        _state.value = GuestRoomUi.Opening
        scope.launch {
            try {
                val room = api.openRoom()
                _exchanged.value = emptyList()
                _state.value = GuestRoomUi.Open(room.roomId, link(room.secret), clock() + Limits.GUEST_ROOM_TTL_MS, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                // A room is already open (e.g. from another device of ours): show that one instead.
                if (e.kind == RelayErrorKind.ALREADY_SENT && adoptCurrent(api)) return@launch
                fail(e.userMessage ?: "Couldn't open the guest room")
            }
        }
    }

    /** Ends the room. */
    fun end() {
        val open = _state.value as? GuestRoomUi.Open ?: return
        val api = api() ?: return
        scope.launch {
            try {
                api.endRoom(open.roomId)
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                // Already gone is as good as ended.
                if (e.kind == RelayErrorKind.NOT_FOUND) close() else fail(e.userMessage ?: "Couldn't end the guest room")
            }
        }
    }

    /** Picks up a room that is still open on the relay (app start, reconnect). */
    fun restore() {
        val api = api() ?: return
        scope.launch {
            try {
                adoptCurrent(api)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RelayException) {
                // Keep what is shown; the next restore or event settles it.
            }
        }
    }

    fun onEvent(e: RelayEvent) {
        when (e) {
            is RelayEvent.GuestJoined -> _state.update { s ->
                if (s is GuestRoomUi.Open && s.roomId == e.roomId) s.copy(guest = e.guest) else s
            }
            is RelayEvent.RoomEnded -> if ((_state.value as? GuestRoomUi.Open)?.roomId == e.roomId) close()
            is RelayEvent.Downloaded -> _exchanged.update { list ->
                list.map { if (it.id in e.fileIds) it.copy(downloadedByGuest = true) else it }
            }
            else -> Unit
        }
    }

    /** Adds [item] to the exchange list, or replaces the entry with its id (progress updates). */
    fun record(item: Exchanged) {
        _exchanged.update { list ->
            if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it } else list + item
        }
    }

    /** Shows the relay's open room; false when there is none. */
    private suspend fun adoptCurrent(api: RelayApi): Boolean {
        val status = api.currentRoom()
        if (status == null) {
            if (_state.value is GuestRoomUi.Open) close()
            return false
        }
        // The relay keeps only a hash of the secret, so the link is known just to the device session that opened the room.
        val link = (_state.value as? GuestRoomUi.Open)?.takeIf { it.roomId == status.roomId }?.link.orEmpty()
        _state.value = GuestRoomUi.Open(status.roomId, link, clock() + (status.expiresAtEpochMs - status.serverNowEpochMs), status.guest)
        return true
    }

    private fun close() { _state.value = GuestRoomUi.Closed; _exchanged.value = emptyList() }

    private fun fail(message: String) { _state.value = GuestRoomUi.Failed(message) }
}
