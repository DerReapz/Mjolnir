package app.mjolnir.android.chat

import app.mjolnir.android.receive.FilePublisher
import app.mjolnir.client.ChatMediaCache
import app.mjolnir.client.PendingSend
import app.mjolnir.client.Thumbnailer
import java.io.File
import java.util.UUID

/** What an import for a chat produced: the copied [files] (named as picked) and the notices to show. */
data class ChatImport(val files: List<File>, val notices: List<String>)

/** Copies picked or shared content (`content://` URIs, as strings) into [dir] before a chat send. Blocking. */
fun interface ChatFileImporter {
    fun import(uris: List<String>, dir: File): ChatImport
}

/**
 * The app's copies of files being sent to a chat, one folder per send under [root]. A share only grants temporary read
 * access and the send may outlive the process, so the bytes are copied in first; the copies are deleted once no pending
 * send needs them any more ([prune], run whenever the pending sends change and at start).
 */
class ChatSendFiles(private val root: File) {
    private val preparing = HashSet<String>()

    /** A new, empty folder for one send's copies; [prune] leaves it alone until [done]. */
    fun newDir(): File = synchronized(this) {
        val dir = File(root, UUID.randomUUID().toString())
        dir.mkdirs()
        preparing += dir.name
        dir
    }

    /** The copy into [dir] finished: when the send was not [queued] (nothing to send, or it was refused) its copies go now. */
    fun done(dir: File, queued: Boolean) {
        synchronized(this) { preparing -= dir.name }
        if (!queued) dir.deleteRecursively()
    }

    /** Deletes every send folder that is neither being prepared nor holds a file of a [pending] send. */
    fun prune(pending: List<PendingSend>) {
        val prefix = root.absolutePath + File.separator
        val needed = pending.flatMap { it.files }.mapNotNullTo(HashSet()) { f ->
            File(f.path).absolutePath.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.substringBefore(File.separatorChar)
        }
        val keep = synchronized(this) { preparing + needed }
        root.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.deleteRecursively() }
    }
}

/**
 * Everything the chat screens need for media: the [cache] the bubbles load from, the [thumbnailer] for sends, the
 * [publisher] that saves downloads to Downloads, the [sendFiles] copies, the [importer] of picked files, and [exportDir]
 * (in the app's cache, offered through the FileProvider) where files are staged for Download and Share….
 */
class ChatMediaSupport(
    val cache: ChatMediaCache,
    val thumbnailer: Thumbnailer,
    val publisher: FilePublisher,
    val sendFiles: ChatSendFiles,
    val importer: ChatFileImporter,
    val exportDir: File,
)
