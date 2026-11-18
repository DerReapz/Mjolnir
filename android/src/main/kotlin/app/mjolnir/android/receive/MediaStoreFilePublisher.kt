package app.mjolnir.android.receive

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import app.mjolnir.android.data.SavedFile
import app.mjolnir.client.ReceivedPath
import app.mjolnir.protocol.FileRef
import java.io.File
import java.io.IOException

/** The folder under shared storage where received files go. */
const val SAVE_ROOT = "Download/Mjolnir/"

/**
 * Publishes received files to `Download/Mjolnir` through MediaStore. Each row stays pending (invisible to other apps)
 * until its bytes are fully copied, so no partial file ever shows up in Downloads. MediaStore itself appends
 * " (1)" etc. when a name is taken, so nothing is overwritten.
 */
class MediaStoreFilePublisher(context: Context) : FilePublisher {
    private val resolver = context.applicationContext.contentResolver
    private val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    override fun publish(part: File, target: ReceivedPath, ref: FileRef): SavedFile {
        val mime = mimeTypeFor(target.fileName, ref.mimeType)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, SAVE_ROOT + (target.folder?.plus("/") ?: ""))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused ${target.fileName}")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Can't write ${target.fileName}")
            out.use { o -> part.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return SavedFile(displayName(uri) ?: target.fileName, uri.toString(), part.length(), mime)
    }

    override fun folderExists(name: String): Boolean {
        val escaped = (SAVE_ROOT + name).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'",
            arrayOf("$escaped/%"),
            null,
        )?.use { return it.moveToFirst() }
        return false
    }

    /** The name MediaStore actually gave the file (it may have added " (1)"). */
    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun mimeTypeFor(fileName: String, sent: String): String {
        if (sent.isNotBlank() && sent != "application/octet-stream") return sent
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
