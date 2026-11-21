package app.mjolnir.android.ui

import androidx.lifecycle.ViewModel
import app.mjolnir.client.Exchanged
import app.mjolnir.client.GuestRoomController
import app.mjolnir.client.GuestRoomUi
import app.mjolnir.client.Messages
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Tab
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The top-level file or folder name of [relativePath] (a folder's name for anything inside one). */
private fun unitName(relativePath: String) = if ('/' in relativePath) relativePath.substringBefore('/') else relativePath

/**
 * What passes through the room besides the room's own events: the rows for what this phone sent to the guest (marked
 * downloaded once every file of a row was), kept in the app so a background throw can record into an open room.
 */
class GuestTraffic(private val controller: GuestRoomController) {
    private val rowOfFile = ConcurrentHashMap<String, String>()
    private val rowFiles = ConcurrentHashMap<String, Set<String>>()
    private val downloaded = ConcurrentHashMap.newKeySet<String>()

    /** True when [deviceId] is the guest of the open room. */
    fun isGuest(deviceId: String): Boolean = (controller.state.value as? GuestRoomUi.Open)?.guest?.deviceId == deviceId

    fun onEvent(e: RelayEvent) {
        controller.onEvent(e)
        when (e) {
            is RelayEvent.Downloaded -> {
                downloaded += e.fileIds
                for (id in e.fileIds.mapNotNull(rowOfFile::get).distinct()) {
                    if (!downloaded.containsAll(rowFiles[id].orEmpty())) continue
                    controller.exchanged.value.find { it.id == id }?.let { controller.record(it.copy(downloadedByGuest = true)) }
                }
            }
            is RelayEvent.RoomEnded -> { rowOfFile.clear(); rowFiles.clear(); downloaded.clear() }
            else -> Unit
        }
    }

    /** One YOU SENT row per link in [tabs], which was sent to the guest. */
    fun recordLinks(tabs: List<Tab>) {
        val at = System.nanoTime()
        tabs.forEachIndexed { i, tab -> controller.record(Exchanged("out:link:$at:$i", false, tab.title?.takeIf { it.isNotBlank() } ?: tab.url, "Link", null, false, null)) }
    }

    /** One YOU SENT row per top-level file or folder of [item], which has been uploaded and sent to the guest. */
    fun recordSent(item: OutgoingFiles) {
        for ((i, entry) in item.files.groupBy { unitName(it.relativePath) }.entries.withIndex()) {
            val (title, files) = entry
            val id = "out:${item.id}:$i"
            val bytes = files.sumOf { it.sizeBytes }
            val detail = if (files.first().relativePath.contains('/')) "Folder · ${files.size} ${if (files.size == 1) "file" else "files"} · ${Messages.size(bytes)}" else Messages.size(bytes)
            val ids = files.mapNotNull { it.ref?.id }.toSet()
            rowFiles[id] = ids
            ids.forEach { rowOfFile[it] = id }
            controller.record(Exchanged(id, false, title, detail, null, ids.isNotEmpty() && downloaded.containsAll(ids), null))
        }
    }
}

/** The Guest room screen's state, over the shared [GuestRoomController]. [events] is the relay's live event stream. */
class GuestRoomViewModel(
    private val controller: GuestRoomController,
    private val traffic: GuestTraffic,
    private val events: () -> Flow<RelayEvent>,
) : ViewModel() {
    val state: StateFlow<GuestRoomUi> = controller.state
    val exchanged: StateFlow<List<Exchanged>> = controller.exchanged

    init { restore() }

    fun open() = controller.open()
    fun end() = controller.end()

    /** Picks up a room still open on the relay; call whenever the screen is entered. */
    fun restore() = controller.restore()

    /** Feeds relay events to the room until cancelled (while the screen is open). A dropped or rejected connection just ends it. */
    suspend fun collectEvents() {
        try {
            events().collect { e ->
                if (e is RelayEvent.Connected) controller.restore() // events may have been missed while reconnecting
                traffic.onEvent(e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: RelayException) {
            // The next screen entry restores the room's state.
        }
    }
}
