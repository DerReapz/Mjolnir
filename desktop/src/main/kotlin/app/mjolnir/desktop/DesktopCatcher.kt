package app.mjolnir.desktop

import app.mjolnir.client.Messages
import app.mjolnir.client.RelayApi
import app.mjolnir.client.receiveFiles
import app.mjolnir.client.uniqueTarget
import app.mjolnir.protocol.Delivery
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DesktopCatcher(
    private val api: RelayApi,
    private val opener: TabOpener,
    private val saveDir: () -> File,
    private val notify: (String) -> Unit,
    private val markDownloaded: (File) -> Unit = ::markOfTheWeb,
) {
    /** Saves every file (all-or-nothing), then opens the tabs, then notifies. A failure propagates. */
    suspend fun handle(d: Delivery) {
        val root = saveDir()
        val saved = receiveFiles(
            api, d.files,
            stagingDir = File(root, ".mjolnir-partial"),
            folderExists = { File(root, it).exists() },
        ) { part, target, _ ->
            val dir = File(root, target.folder ?: "").apply { mkdirs() }
            val dest = uniqueTarget(dir, target.fileName)
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            markDownloaded(dest)
            dest
        }
        val tabsOpened = opener.open(d.tabs)
        if (tabsOpened > 0 || saved.isNotEmpty()) notify(Messages.caught(tabsOpened, saved.size, d.from.name))
    }
}
