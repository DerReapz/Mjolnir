package app.mjolnir.desktop

import app.mjolnir.client.Messages
import app.mjolnir.client.ReceivedPath
import app.mjolnir.client.RelayApi
import app.mjolnir.client.receiveFiles
import app.mjolnir.client.uniqueTarget
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.Peer
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
) {
    /** Saves every file (all-or-nothing), then opens the tabs, then notifies. A save failure propagates; a notify failure does not. */
    suspend fun handle(d: Delivery) {
        val root = saveDir()
        val saved = receiveFiles(
            api, d.files,
            stagingDir = File(root, ".mjolnir-partial"),
            folderExists = { File(root, it).exists() },
        ) { part, target, _ ->
            val dir = File(root, target.folder ?: "").apply { mkdirs() }
            val dest = uniqueTarget(dir, target.fileName)
            // uniqueTarget chose a free name; if one appeared meanwhile, failing beats overwriting it
            Files.move(part.toPath(), dest.toPath())
            markDownloaded(dest)
            target to dest
        }
        val tabsOpened = if (openLinks()) opener.open(d.tabs) else d.tabs.count { isSafeTabUrl(it.url) }
        record(d, root, saved)
        if (tabsOpened > 0 || saved.isNotEmpty()) {
            // A failed notification must not fail the delivery: a redelivery would save the files again.
            try {
                notify(Messages.caught(tabsOpened, saved.size, displayName(d.from)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ignored
            }
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
            ReceivedEntry("${d.id}:$i", d.from.deviceId, d.from.name, now, kind, title, target)
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
