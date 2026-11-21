package app.mjolnir.client

import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

sealed interface GuestRoomUi {
    data object Closed : GuestRoomUi
    data object Opening : GuestRoomUi
    /**
     * [expiresAtEpochMs] is on the device's clock (see [GuestRoomController]); [guest] is null until someone joins.
     * [error] is the reason the last action on the open room (ending it) failed; the room is still open.
     */
    data class Open(val roomId: String, val link: String, val expiresAtEpochMs: Long, val guest: Peer?, val error: String? = null) : GuestRoomUi
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
 * `clock()` plus the time the relay says is left, so a wrong device clock doesn't matter. The relay keeps only a hash
 * of a room's secret, so the secret is kept in [secrets] (roomId to secret) to rebuild the link after a restart.
 */
class GuestRoomController(
    private val api: () -> RelayApi?,
    private val relayUrl: () -> String,
    private val secrets: JsonFileStore<Map<String, String>>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow<GuestRoomUi>(GuestRoomUi.Closed)
    val state: StateFlow<GuestRoomUi> = _state.asStateFlow()

    private val _exchanged = MutableStateFlow<List<Exchanged>>(emptyList())
    val exchanged: StateFlow<List<Exchanged>> = _exchanged.asStateFlow()

    /** Bumped by every change of the room's state, so a slow [restore] that began earlier knows its answer is stale. */
    private val epoch = AtomicInteger()

    private fun link(secret: String) = "${relayUrl().trimEnd('/')}/g/$secret"

    /** Opens a room; does nothing while one is opening or open. */
    fun open() {
        if (_state.value is GuestRoomUi.Opening || _state.value is GuestRoomUi.Open) return
        val api = api() ?: return fail("Not connected to the relay")
        epoch.incrementAndGet()
        _state.value = GuestRoomUi.Opening
        scope.launch {
            try {
                val room = api.openRoom()
                _exchanged.value = emptyList()
                saveSecrets { mapOf(room.roomId to room.secret) }
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
                if (e.kind == RelayErrorKind.NOT_FOUND) close()
                else _state.update { s -> if (s is GuestRoomUi.Open) s.copy(error = e.userMessage ?: "Couldn't end the guest room") else s }
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
            is RelayEvent.RoomEnded -> {
                epoch.incrementAndGet()
                if ((_state.value as? GuestRoomUi.Open)?.roomId == e.roomId) close()
                saveSecrets { it - e.roomId }
            }
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

    /**
     * Shows the relay's open room if its secret is stored; false when there is no such room. Stored secrets of any
     * other room are dropped. An answer that arrives after the room's state changed meanwhile is discarded (true).
     */
    private suspend fun adoptCurrent(api: RelayApi): Boolean {
        val started = epoch.get()
        val status = api.currentRoom()
        if (epoch.get() != started) return true
        val secret = status?.let { s -> secrets.readOrEmpty()[s.roomId] }
        saveSecrets { all -> if (status != null && secret != null) mapOf(status.roomId to secret) else emptyMap() }
        if (status == null || secret == null) {
            if (_state.value is GuestRoomUi.Open) close()
            return false
        }
        _state.value = GuestRoomUi.Open(status.roomId, link(secret), clock() + (status.expiresAtEpochMs - status.serverNowEpochMs), status.guest)
        return true
    }

    private fun close() { epoch.incrementAndGet(); _state.value = GuestRoomUi.Closed; _exchanged.value = emptyList(); saveSecrets { emptyMap() } }

    private fun saveSecrets(change: (Map<String, String>) -> Map<String, String>) {
        try { secrets.update { change(it) } } catch (_: IOException) { /* the link then won't survive a restart */ }
    }

    private fun JsonFileStore<Map<String, String>>.readOrEmpty(): Map<String, String> =
        try { read() } catch (_: IOException) { emptyMap() }

    private fun fail(message: String) { _state.value = GuestRoomUi.Failed(message) }
}

/** The store of the room's secret on the owner's device (roomId to secret), e.g. `guest-room.json` in the app's data folder. */
fun guestRoomSecretStore(file: File) =
    JsonFileStore(file, MapSerializer(String.serializer(), String.serializer())) { emptyMap<String, String>() }
