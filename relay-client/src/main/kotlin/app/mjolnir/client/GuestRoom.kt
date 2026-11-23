package app.mjolnir.client

import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Tab
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** `41:12`: whole minutes and seconds left (never negative). Shared by the desktop and Android apps. */
fun formatCountdown(msLeft: Long): String {
    val s = (msLeft.coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d".format(Locale.ROOT, s / 60, s % 60)
}

/**
 * One row of the room's exchange list. [id] identifies the row: the file id for a row received from the guest, and
 * `out:{item}:{index}` for a row sent to the guest (see [GuestRoomController.recordSent]).
 */
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

    /** Row id of each file sent to the guest, and the files of each row: a row counts as downloaded once all its files were. */
    private val rowOfFile = ConcurrentHashMap<String, String>()
    private val rowFiles = ConcurrentHashMap<String, Set<String>>()
    private val downloaded = ConcurrentHashMap.newKeySet<String>()

    private var expiry: Job? = null

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
                val room = try {
                    api.openRoom()
                } catch (e: RelayException) {
                    // A room is already open: show it if its secret is here; else it was ended, so open a fresh one.
                    if (e.kind != RelayErrorKind.ALREADY_SENT || adoptCurrent(api)) throw e
                    api.openRoom()
                }
                resetRows()
                saveSecrets { mapOf(room.roomId to room.secret) }
                val expiresAt = clock() + Limits.GUEST_ROOM_TTL_MS
                _state.value = GuestRoomUi.Open(room.roomId, link(room.secret), expiresAt, null)
                scheduleExpiry(room.roomId, expiresAt)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                fail("Couldn't read the saved guest room link") // an unreadable store says nothing about the room, so none is ended
            } catch (e: RelayException) {
                if (e.kind == RelayErrorKind.ALREADY_SENT && _state.value is GuestRoomUi.Open) return@launch // adopted above
                fail(e.userMessage ?: outdatedOr(e, "Couldn't open the guest room"))
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
                else _state.update { s -> if (s is GuestRoomUi.Open) s.copy(error = e.userMessage ?: outdatedOr(e, "Couldn't end the guest room")) else s }
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
            } catch (_: IOException) {
                // The saved link couldn't be read: that is no reason to end a room, so keep the state and say so.
                val message = "Couldn't read the saved guest room link"
                _state.update { s -> if (s is GuestRoomUi.Open) s.copy(error = message) else GuestRoomUi.Failed(message) }
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
            is RelayEvent.Downloaded -> markDownloaded(e.fileIds)
            else -> Unit
        }
    }

    /**
     * Shows one YOU SENT row [rowId] for what was sent to the guest, made of the files [fileIds]. It counts as downloaded
     * once the guest has downloaded all of them (also if they already had, before this row was recorded).
     */
    fun recordSent(rowId: String, title: String, detail: String, fileIds: Set<String>) {
        rowFiles[rowId] = fileIds
        fileIds.forEach { rowOfFile[it] = rowId }
        record(Exchanged(rowId, false, title, detail, null, fileIds.isNotEmpty() && downloaded.containsAll(fileIds), null))
    }

    /** One YOU SENT row per link in [tabs]; [batchId] tells this send's rows from every other's. */
    fun recordLinks(batchId: String, tabs: List<Tab>) {
        tabs.forEachIndexed { i, tab ->
            record(Exchanged("out:link:$batchId:$i", false, tab.title?.takeIf { it.isNotBlank() } ?: tab.url, "Link", null, false, null))
        }
    }

    private fun markDownloaded(fileIds: List<String>) {
        downloaded += fileIds
        val rows = fileIds.mapNotNull(rowOfFile::get).distinct().filter { downloaded.containsAll(rowFiles[it].orEmpty()) }.toSet()
        if (rows.isEmpty()) return
        _exchanged.update { list -> list.map { if (it.id in rows) it.copy(downloadedByGuest = true) else it } }
    }

    /** Adds [item] to the exchange list, or replaces the entry with its id (progress updates). */
    fun record(item: Exchanged) {
        _exchanged.update { list ->
            if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it } else list + item
        }
    }

    /**
     * Shows the relay's open room if its secret is stored; else false (a room without its secret is ended). Stored secrets of any
     * other room are dropped. An answer that arrives after the room's state changed meanwhile is discarded (true).
     */
    private suspend fun adoptCurrent(api: RelayApi): Boolean {
        val started = epoch.get()
        val status = api.currentRoom()
        if (epoch.get() != started) return true
        // A store that can't be read throws (IOException) and leaves everything as it is; only a store read successfully
        // without this room's entry means the secret is lost.
        val secret = status?.let { s -> secrets.read()[s.roomId] }
        saveSecrets { all -> if (status != null && secret != null) mapOf(status.roomId to secret) else emptyMap() }
        if (status == null || secret == null) {
            if (_state.value is GuestRoomUi.Open) close()
            // Rooms belong to one owner device, so an open room whose secret is gone is unusable: end it.
            if (status != null) {
                try { api.endRoom(status.roomId) } catch (e: RelayException) { if (e.kind != RelayErrorKind.NOT_FOUND) throw e }
            }
            return false
        }
        val expiresAt = clock() + (status.expiresAtEpochMs - status.serverNowEpochMs)
        if ((_state.value as? GuestRoomUi.Open)?.roomId != status.roomId) resetRows()
        _state.value = GuestRoomUi.Open(status.roomId, link(secret), expiresAt, status.guest)
        scheduleExpiry(status.roomId, expiresAt)
        // Downloads that happened while no app was listening.
        markDownloaded(status.downloadedFileIds)
        return true
    }

    /** Closes the room when its time is up, without waiting for the relay to say so. */
    private fun scheduleExpiry(roomId: String, expiresAt: Long) {
        expiry?.cancel()
        expiry = scope.launch {
            val left = expiresAt - clock()
            if (left > 0) delay(left)
            if (clock() >= expiresAt && (_state.value as? GuestRoomUi.Open)?.roomId == roomId) close()
        }
    }

    private fun resetRows() { _exchanged.value = emptyList(); rowOfFile.clear(); rowFiles.clear(); downloaded.clear() }

    private fun close() {
        epoch.incrementAndGet()
        expiry?.cancel()
        _state.value = GuestRoomUi.Closed
        resetRows()
        saveSecrets { emptyMap() }
    }

    private fun saveSecrets(change: (Map<String, String>) -> Map<String, String>) {
        try { secrets.update { change(it) } } catch (_: IOException) { /* the link then won't survive a restart */ }
    }

    private fun fail(message: String) { _state.value = GuestRoomUi.Failed(message) }

    private fun outdatedOr(e: RelayException, generic: String) = if (e.needsRelayUpdate) Messages.RELAY_OUTDATED else generic
}

/** The store of the room's secret on the owner's device (roomId to secret), e.g. `guest-room.json` in the app's data folder. */
fun guestRoomSecretStore(file: File) =
    JsonFileStore(file, MapSerializer(String.serializer(), String.serializer())) { emptyMap<String, String>() }
