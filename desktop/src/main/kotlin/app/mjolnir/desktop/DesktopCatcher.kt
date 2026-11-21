package app.mjolnir.desktop

import app.mjolnir.client.Exchanged
import app.mjolnir.client.Messages
import app.mjolnir.client.ReceivedPath
import app.mjolnir.client.RelayApi
import app.mjolnir.client.receiveFiles
import app.mjolnir.client.uniqueTarget
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.topFolder
import app.mjolnir.protocol.isSafeTabUrl
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException

class DesktopCatcher(
    private val api: RelayApi,
    private val opener: TabOpener,
    private val saveDir: () -> File,
    private val notify: (String) -> Unit,
    private val history: ReceivedHistory,
    private val openLinks: () -> Boolean,
    private val displayName: (Peer) -> String,
    private val markDownloaded: (File) -> Unit = ::markOfTheWeb,
    /** Gets the guest room's exchange rows for what a guest sends: progress while downloading, then one row per saved file or folder. */
    private val recordGuest: (Exchanged) -> Unit = {},
) {
    /** Saves every file (all-or-nothing), then opens the tabs, then notifies. A save failure propagates; a notify failure does not. */
    suspend fun handle(d: Delivery) {
        val root = saveDir()
        val fromGuest = d.from.kind == DeviceKind.GUEST
        val progress = if (fromGuest) guestProgress(d) else null
        val saved = receiveFiles(
            api, d.files,
            stagingDir = File(root, ".mjolnir-partial"),
            folderExists = { File(root, it).exists() },
            onProgress = { ref, bytes -> progress?.invoke(ref.id, bytes) },
        ) { part, target, _ ->
            val dir = File(root, target.folder ?: "").apply { mkdirs() }
            val dest = uniqueTarget(dir, target.fileName)
            // uniqueTarget chose a free name; if one appeared meanwhile, failing beats overwriting it
            Files.move(part.toPath(), dest.toPath())
            markDownloaded(dest)
            target to dest
        }
        // A guest is a stranger with a browser: its links are recorded and announced but never opened on their own.
        val autoOpen = openLinks() && d.from.kind != DeviceKind.GUEST
        val tabsOpened = if (autoOpen) opener.open(d.tabs) else d.tabs.count { isSafeTabUrl(it.url) }
        record(d, root, saved)
        if (fromGuest) recordGuestItems(d, root, saved)
        if (tabsOpened > 0 || saved.isNotEmpty()) {
            // A failed notification must not fail the delivery: a redelivery would save the files again.
            try {
                notify(Messages.caught(tabsOpened, saved.size, displayName(d.from), d.space?.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ignored
            }
        }
    }

    /** Reports the delivery's progress as its first exchange row (the one [recordGuestItems] later replaces). */
    private fun guestProgress(d: Delivery): (String, Long) -> Unit {
        val total = d.files.sumOf { it.sizeBytes }
        val title = d.files.firstOrNull()?.let { topFolder(it.relativePath) ?: it.name } ?: return { _, _ -> }
        val got = HashMap<String, Long>()
        return { id, bytes ->
            got[id] = bytes
            val done = got.values.sum().coerceAtMost(total)
            val fraction = if (total > 0) done.toFloat() / total else 1f
            recordGuest(Exchanged("in:${d.id}:0", true, title, "${Messages.size(done)} of ${Messages.size(total)} · ${(fraction * 100).toInt()}%", fraction, false, null))
        }
    }

    /** One finished row per top-level file or received folder. */
    private fun recordGuestItems(d: Delivery, root: File, saved: List<Pair<ReceivedPath, File>>) {
        // A folder is one unit (keyed by its top name); every loose file is its own (keyed by its place in the delivery).
        val units = LinkedHashMap<Any, MutableList<Pair<ReceivedPath, File>>>()
        saved.forEachIndexed { n, item -> units.getOrPut(item.first.folder?.substringBefore('/') ?: n) { mutableListOf() } += item }
        units.entries.forEachIndexed { i, (key, items) ->
            val bytes = items.sumOf { it.second.length() }
            val where = "saved to ${root.absolutePath}"
            val row = if (key !is String) {
                val file = items.single().second
                Exchanged("in:${d.id}:$i", true, file.name, "${Messages.size(bytes)} · $where", null, false, file.absolutePath)
            } else {
                Exchanged("in:${d.id}:$i", true, key, "Folder · ${items.size} ${if (items.size == 1) "file" else "files"} · ${Messages.size(bytes)} · $where", null, false, File(root, key).absolutePath)
            }
            recordGuest(row)
        }
    }

    /** Adds one entry per tab, per top-level file, and per received folder; a history failure must not fail the delivery. */
    private fun record(d: Delivery, root: File, saved: List<Pair<ReceivedPath, File>>) {
        val items = mutableListOf<Triple<Kind, String, String>>()
        d.tabs.filter { isSafeTabUrl(it.url) }.forEach { items += Triple(Kind.LINK, it.title?.takeIf(String::isNotBlank) ?: it.url, it.url) }
        val folders = linkedSetOf<String>()
        for ((path, file) in saved) {
            val top = path.folder?.substringBefore('/')
            if (top == null) items += Triple(Kind.FILE, file.name, file.absolutePath)
            else if (folders.add(top)) items += Triple(Kind.FOLDER, top, File(root, top).absolutePath)
        }
        val now = System.currentTimeMillis()
        val entries = items.mapIndexed { i, (kind, title, target) ->
            ReceivedEntry("${d.id}:$i", d.from.deviceId, if (d.from.kind == DeviceKind.GUEST) "Guest" else d.from.name, now, kind, title, target, d.space?.name)
        }
        try {
            history.add(entries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ignored
        }
    }
}
