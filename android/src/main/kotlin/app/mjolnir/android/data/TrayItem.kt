package app.mjolnir.android.data

import app.mjolnir.client.LocalUpload
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.topFolder
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Something waiting in the phone's tray to be thrown. */
@Serializable
sealed interface TrayItem {
    val id: String

    @Serializable @SerialName("link")
    data class Link(val tab: Tab) : TrayItem {
        override val id: String get() = tab.url
    }

    /** A shared file, copied to [path] inside the app's outbox directory. */
    @Serializable @SerialName("file")
    data class LocalFile(
        override val id: String,
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val mimeType: String,
    ) : TrayItem

    /** A picked folder; each file's path is a content:// URI read in place. */
    @Serializable @SerialName("folder")
    data class Folder(override val id: String, val name: String, val files: List<LocalUpload>) : TrayItem
}

fun TrayItem.toUploads(): List<LocalUpload> = when (this) {
    is TrayItem.Link -> emptyList()
    is TrayItem.LocalFile -> listOf(LocalUpload(path, name, mimeType, sizeBytes))
    is TrayItem.Folder -> files
}

/**
 * Puts the files of a failed background send back in the tray: files under a top folder are regrouped into one
 * Folder per top folder, the rest become LocalFiles. Relay upload state is dropped so a new throw starts afresh.
 */
fun returnToTray(tray: List<TrayItem>, item: OutgoingFiles): List<TrayItem> {
    val files = item.files.map { it.copy(uploadId = null, ref = null) }
    val byFolder = files.groupBy { topFolder(it.relativePath) }
    val seen = HashSet<String>()
    val returned = mutableListOf<TrayItem>()
    for (file in files) {
        val top = topFolder(file.relativePath)
        if (top == null) {
            returned += TrayItem.LocalFile(UUID.randomUUID().toString(), file.path, file.relativePath, file.sizeBytes, file.mimeType)
        } else if (seen.add(top)) {
            returned += TrayItem.Folder(UUID.randomUUID().toString(), top, byFolder.getValue(top))
        }
    }
    return tray + returned
}
