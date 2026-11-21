package app.mjolnir.android.receive

import app.mjolnir.android.data.CaughtItem
import app.mjolnir.android.data.SavedFile
import app.mjolnir.android.data.SavedFolder
import app.mjolnir.android.send.NetDecision
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Messages
import app.mjolnir.client.ReceivedPath
import app.mjolnir.client.RelayApi
import app.mjolnir.client.receiveFiles
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.cleanTabs
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Where received files end up (MediaStore Downloads on the phone). */
interface FilePublisher {
    /** Publishes the finished [part] at [target] without overwriting anything; returns what was saved. */
    fun publish(part: File, target: ReceivedPath, ref: FileRef): SavedFile

    /** Whether a top folder [name] already exists in the save location. */
    fun folderExists(name: String): Boolean
}

/** Thrown by [PhoneCatcher.handle] for a delivery that waits for Wi-Fi; the drainer leaves it unacked. */
class WaitingForWifi(val delivery: Delivery) : Exception()

/** Most recent rows kept on the Caught screen. */
const val MAX_CAUGHT_ITEMS = 200

/**
 * Receives one delivery on the phone: downloads its files (all-or-nothing, resumable) unless they wait for Wi-Fi,
 * publishes them, stores the safe tabs and the saved files in [caught], and notifies.
 */
class PhoneCatcher(
    private val api: RelayApi,
    private val caught: JsonFileStore<List<CaughtItem>>,
    private val stagingDir: File,
    private val publisher: FilePublisher,
    private val notify: (String) -> Unit,
    private val decide: (Delivery) -> NetDecision,
    private val now: () -> Long = System::currentTimeMillis,
    /** The name to show for a sender (its nickname when one is set). */
    private val displayName: (Peer) -> String = { it.name },
    /** Called once a delivery's files are about to be downloaded (e.g. to go foreground). */
    private val beforeDownload: suspend (Delivery) -> Unit = {},
    /** Bytes of [FileRef] held locally so far, for a progress notification. */
    private val onProgress: (Delivery, FileRef, Long) -> Unit = { _, _, _ -> },
) {
    private val waitingList = mutableListOf<Delivery>()

    /** Deliveries that [handle] left for Wi-Fi since the last [resetWaiting]. */
    val waiting: List<Delivery> get() = synchronized(waitingList) { waitingList.toList() }

    fun resetWaiting() = synchronized(waitingList) { waitingList.clear() }

    suspend fun handle(d: Delivery) {
        if (d.files.isNotEmpty() && decide(d) == NetDecision.WAIT_FOR_WIFI) {
            synchronized(waitingList) { if (waitingList.none { it.id == d.id }) waitingList += d }
            throw WaitingForWifi(d)
        }
        val published: List<Pair<ReceivedPath, SavedFile>> = if (d.files.isEmpty()) emptyList() else {
            beforeDownload(d)
            receiveFiles(api, d.files, stagingDir, publisher::folderExists, onProgress = { ref, bytes -> onProgress(d, ref, bytes) }) { part, target, ref ->
                target to publisher.publish(part, target, ref)
            }
        }
        val tabs = cleanTabs(d.tabs)
        val at = now()
        val items = buildList {
            val folders = published.filter { it.first.folder != null }.groupBy { it.first.folder!!.substringBefore('/') }
            for ((name, files) in folders) {
                add(CaughtItem(folder = SavedFolder(name, files.size, files.sumOf { it.second.sizeBytes }), fromName = d.from.name, caughtAtEpochMs = at, fromDeviceId = d.from.deviceId, spaceName = d.space?.name))
            }
            for ((target, saved) in published) {
                if (target.folder == null) add(CaughtItem(file = saved, fromName = d.from.name, caughtAtEpochMs = at, fromDeviceId = d.from.deviceId, spaceName = d.space?.name))
            }
            for (tab in tabs) add(CaughtItem(tab = tab, fromName = d.from.name, caughtAtEpochMs = at, fromDeviceId = d.from.deviceId, spaceName = d.space?.name))
        }
        if (items.isEmpty()) return
        try {
            caught.update { (items + it).take(MAX_CAUGHT_ITEMS) }
        } catch (e: IOException) {
            // Files are already in Downloads: a redelivery would save them twice, so only the history row is lost.
            // Tabs exist nowhere else: fail so the delivery stays unacked and comes back.
            if (published.isEmpty()) throw e
        }
        try {
            notify(Messages.caught(tabs.size, published.size, displayName(d.from), d.space?.name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed notification must not fail the delivery.
        }
    }
}
