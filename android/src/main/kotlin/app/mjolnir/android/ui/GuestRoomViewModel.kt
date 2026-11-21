package app.mjolnir.android.ui

import androidx.lifecycle.ViewModel
import app.mjolnir.client.Exchanged
import app.mjolnir.client.GuestRoomController
import app.mjolnir.client.GuestRoomUi
import app.mjolnir.client.Messages
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.protocol.Tab
import kotlinx.coroutines.flow.StateFlow

/** The top-level file or folder name of [relativePath] (a folder's name for anything inside one). */
private fun unitName(relativePath: String) = if ('/' in relativePath) relativePath.substringBefore('/') else relativePath

/**
 * Turns what this phone sends to the guest into rows of the room's exchange list (kept in the app, so a background throw
 * can record into an open room). The rows and their downloaded marks live in the [GuestRoomController].
 */
class GuestTraffic(private val controller: GuestRoomController) {
    /** True when [deviceId] is the guest of the open room. */
    fun isGuest(deviceId: String): Boolean = (controller.state.value as? GuestRoomUi.Open)?.guest?.deviceId == deviceId

    /** One YOU SENT row per link in [tabs], which was sent to the guest. */
    fun recordLinks(tabs: List<Tab>) = controller.recordLinks(System.nanoTime().toString(), tabs)

    /** One YOU SENT row per top-level file or folder of [item], which has been uploaded and sent to the guest. */
    fun recordSent(item: OutgoingFiles) {
        for ((i, entry) in item.files.groupBy { unitName(it.relativePath) }.entries.withIndex()) {
            val (title, files) = entry
            val bytes = files.sumOf { it.sizeBytes }
            val detail = if (files.first().relativePath.contains('/')) "Folder · ${files.size} ${if (files.size == 1) "file" else "files"} · ${Messages.size(bytes)}" else Messages.size(bytes)
            controller.recordSent("out:${item.id}:$i", title, detail, files.mapNotNull { it.ref?.id }.toSet())
        }
    }
}

/** The Guest room screen's state, over the shared [GuestRoomController]. The relay's events reach the room through [GuestRoomWatcher]. */
class GuestRoomViewModel(private val controller: GuestRoomController) : ViewModel() {
    val state: StateFlow<GuestRoomUi> = controller.state
    val exchanged: StateFlow<List<Exchanged>> = controller.exchanged

    init { restore() }

    fun open() = controller.open()
    fun end() = controller.end()

    /** Picks up a room still open on the relay; call whenever the screen is entered. */
    fun restore() = controller.restore()
}
