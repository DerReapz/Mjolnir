package app.mjolnir.client

import app.mjolnir.protocol.Attachment
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Downloaded chat media (files and thumbnails) in [dir], one file per relay file id, kept to [capBytes] by evicting the
 * least recently used. A pinned id (a bubble on screen, a video playing) is never evicted, even when it alone is over the
 * cap; it goes as soon as it is unpinned while the cache is over the cap (ruling CV-P1). Survives restarts.
 */
class ChatMediaCache(private val dir: File, private val capBytes: Long = 512L shl 20) {
    private val lock = Any()
    /** id → size, least recently used first. */
    private val entries = LinkedHashMap<String, Long>(16, 0.75f, true)
    private val pins = HashMap<String, Int>()
    private val downloads = ConcurrentHashMap<String, Mutex>()

    init {
        dir.mkdirs()
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        files.filter { it.name.endsWith(PART) }.forEach { it.delete() } // an interrupted download
        synchronized(lock) {
            files.filter { !it.name.endsWith(PART) }.sortedBy { it.lastModified() }.forEach { entries[it.name] = it.length() }
        }
    }

    /**
     * The attachment's file, downloaded first unless cached, reporting (bytes so far, [Attachment.sizeBytes]) to
     * [onProgress] while it downloads. Throws the [RelayException] of a failed download (NOT_FOUND: its message was
     * deleted), and NETWORK "incomplete_download" when fewer or more bytes than the attachment's size arrived.
     */
    suspend fun get(att: Attachment, api: RelayApi, onProgress: (Long, Long) -> Unit = { _, _ -> }): File =
        file(att.fileId, api, att.sizeBytes) { onProgress(it, att.sizeBytes) }

    /** The attachment's thumbnail (null when it has none), downloaded first unless cached. */
    suspend fun thumbnail(att: Attachment, api: RelayApi): File? = att.thumbFileId?.let { file(it, api, null) {} }

    /** Keeps [fileId] from being evicted until a matching [unpin]; pins count, so two bubbles can show the same file. */
    fun pin(fileId: String) = synchronized(lock) { pins[fileId] = (pins[fileId] ?: 0) + 1 }

    fun unpin(fileId: String) = synchronized(lock) {
        val n = (pins[fileId] ?: return@synchronized) - 1
        if (n > 0) pins[fileId] = n
        else {
            pins.remove(fileId)
            evict(keep = null) // a pinned video over the cap goes now
        }
    }

    /** Deletes everything cached (the device moved to another relay or was reset). */
    fun clear() = synchronized(lock) {
        entries.clear()
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    /** [id]'s file, downloaded unless cached; a download must come to [expectedBytes] when that is known. */
    private suspend fun file(id: String, api: RelayApi, expectedBytes: Long?, onProgress: (Long) -> Unit): File {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "unexpected file id $id" }
        val target = File(dir, id)
        return downloads.computeIfAbsent(id) { Mutex() }.withLock {
            val hit = synchronized(lock) { entries[id] != null && target.isFile } // the lookup marks it used
            if (hit) {
                target.setLastModified(System.currentTimeMillis()) // the order after a restart
                return@withLock target
            }
            val part = File(dir, id + PART)
            try {
                api.downloadChatFile(id, part, onProgress)
                withContext(Dispatchers.IO) {
                    if (expectedBytes != null && part.length() != expectedBytes) {
                        throw RelayException(RelayErrorKind.NETWORK, "incomplete_download")
                    }
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { part.delete() }
                throw e
            }
            synchronized(lock) {
                entries[id] = target.length()
                evict(keep = id)
            }
            target
        }
    }

    /** Deletes the least recently used unpinned entries (never [keep]) until the cache fits its cap. */
    private fun evict(keep: String?) {
        var total = entries.values.sum()
        val it = entries.entries.iterator()
        while (total > capBytes && it.hasNext()) {
            val (id, size) = it.next()
            if (id == keep || (pins[id] ?: 0) > 0) continue
            File(dir, id).delete()
            it.remove()
            total -= size
        }
    }

    private companion object {
        const val PART = ".part"
    }
}
