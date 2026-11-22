package app.mjolnir.android.share

import app.mjolnir.android.data.TrayItem
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Messages
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.safeFileName
import app.mjolnir.protocol.safeRelativePath
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

sealed interface ImportResult {
    data class Ok(val item: TrayItem, val notice: String? = null) : ImportResult
    data class Rejected(val notice: String) : ImportResult
}

private const val DEFAULT_MIME = "application/octet-stream"

/**
 * Copies one shared file into [outboxDir] (shares only grant temporary read access), so it can wait in the tray.
 * The copy is named by its id, or with [keepName] by its safe name in a folder of its own (`<id>/<name>`), for a chat
 * send that takes its name and type from the file. A chat send's copy has no size cap: the relay's own per-file limit
 * (`MJOLNIR_MAX_CHAT_FILE_MB`) decides, and a file it refuses fails alone (ruling CM-R7). Blocking: call it off the main thread.
 */
fun importFile(name: String?, sizeBytes: Long?, mimeType: String?, open: () -> InputStream?, outboxDir: File, keepName: Boolean = false): ImportResult {
    val safeName = safeFileName(name ?: "file")
    val max = if (keepName) Long.MAX_VALUE else Limits.MAX_FILE_BYTES
    if (sizeBytes != null && sizeBytes > max) return ImportResult.Rejected(Messages.tooBig(safeName))
    val id = UUID.randomUUID().toString()
    val target = if (keepName) File(File(outboxDir, id), safeName) else File(outboxDir, id)
    val copied = try {
        val input = open() ?: return ImportResult.Rejected(Messages.unreadable(safeName))
        target.parentFile?.mkdirs()
        input.use { copyAtMost(it, target, max) }
    } catch (e: IOException) {
        discard(target, keepName)
        return ImportResult.Rejected(Messages.unreadable(safeName))
    } catch (e: SecurityException) { // the sharing app's permission was revoked
        discard(target, keepName)
        return ImportResult.Rejected(Messages.unreadable(safeName))
    }
    if (copied == null) {
        discard(target, keepName)
        return ImportResult.Rejected(Messages.tooBig(safeName))
    }
    return ImportResult.Ok(TrayItem.LocalFile(id, target.path, safeName, copied, mimeType ?: DEFAULT_MIME))
}

/** Deletes a failed copy (and, for a [keepName] copy, its own folder). */
private fun discard(target: File, keepName: Boolean) {
    target.delete()
    if (keepName) target.parentFile?.delete()
}

/** Copies [input] to [target]; returns the byte count, or null as soon as it would exceed [max]. */
private fun copyAtMost(input: InputStream, target: File, max: Long): Long? = target.outputStream().use { out ->
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
    }
    total
}

/** One file of a shared folder, read in place through its content [uri]. */
data class FolderEntry(val relativePath: String, val uri: String, val sizeBytes: Long, val mimeType: String)

/** Turns a picked folder into one tray item; files over the size limit are left out. */
fun importFolder(name: String, entries: List<FolderEntry>): ImportResult {
    val kept = entries.filter { it.sizeBytes <= Limits.MAX_FILE_BYTES }
    val leftOut = entries.size - kept.size
    if (kept.isEmpty()) return ImportResult.Rejected(Messages.emptyFolder(name))
    if (kept.size > Limits.MAX_FILES_PER_SEND) return ImportResult.Rejected(Messages.tooManyFiles(name))
    val uploads = kept.map { LocalUpload(it.uri, safeRelativePath(it.relativePath), it.mimeType, it.sizeBytes) }
    val folder = TrayItem.Folder(UUID.randomUUID().toString(), name, uploads)
    return ImportResult.Ok(folder, if (leftOut > 0) Messages.leftOut(leftOut) else null)
}
