package app.mjolnir.android.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import app.mjolnir.android.data.TrayItem
import app.mjolnir.protocol.Limits
import java.io.File

/** What an import produced: tray items and the notices to show. */
data class Imported(val items: List<TrayItem>, val notices: List<String>)

/** Android-side glue for [importFile] and [importFolder]. Blocking: call it on an IO dispatcher. */
class UriImporter(private val context: Context, private val outboxDir: File) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun importUris(uris: List<Uri>): Imported {
        val items = mutableListOf<TrayItem>()
        val notices = mutableListOf<String>()
        for (uri in uris) {
            var name: String? = uri.lastPathSegment
            var size: Long? = null
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
            } catch (_: Exception) {
                // Name and size are best effort; the copy below decides.
            }
            val result = importFile(name, size, resolver.getType(uri), { resolver.openInputStream(uri) }, outboxDir)
            when (result) {
                is ImportResult.Ok -> { items += result.item; result.notice?.let(notices::add) }
                is ImportResult.Rejected -> notices += result.notice
            }
        }
        return Imported(items, notices)
    }

    /** Walks a picked folder tree (never deeper than [MAX_DEPTH], never more than one send's worth of files + 1). */
    fun importTree(treeUri: Uri): Imported {
        val root = DocumentFile.fromTreeUri(context, treeUri)
        val name = root?.name ?: return Imported(emptyList(), listOf(app.mjolnir.client.Messages.unreadable("that folder")))
        val entries = mutableListOf<FolderEntry>()
        walk(root, name, 0, entries)
        return when (val r = importFolder(name, entries)) {
            is ImportResult.Ok -> Imported(listOf(r.item), listOfNotNull(r.notice))
            is ImportResult.Rejected -> Imported(emptyList(), listOf(r.notice))
        }
    }

    private fun walk(dir: DocumentFile, path: String, depth: Int, out: MutableList<FolderEntry>) {
        if (depth > MAX_DEPTH) return
        val children = try { dir.listFiles() } catch (_: Exception) { return }
        for (child in children) {
            if (out.size > Limits.MAX_FILES_PER_SEND) return // enough for importFolder to reject it as too big
            val childName = child.name ?: continue
            val childPath = "$path/$childName"
            when {
                child.isDirectory -> walk(child, childPath, depth + 1, out)
                child.isFile -> out += FolderEntry(childPath, child.uri.toString(), child.length(), child.type ?: "application/octet-stream")
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}
