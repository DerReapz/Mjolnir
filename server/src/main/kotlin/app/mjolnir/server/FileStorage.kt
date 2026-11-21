package app.mjolnir.server

import app.mjolnir.protocol.Limits
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Blob storage for uploads. An open upload lives at `<id>.part`, preallocated to its declared size so
 * [committedBytes] counts it in full; a complete file lives at `<id>` (the file id equals the upload id).
 * Bodies are streamed to and from disk in small buffers; a whole file is never held in memory.
 */
class FileStorage(
    val dir: File,
    val maxFileBytes: Long = Limits.MAX_FILE_BYTES,
    val maxTotalBytes: Long = 2_147_483_648L,
    /** Largest upload accepted at all: [maxFileBytes], or more when chat attachments may be larger than a delivered file. */
    val maxUploadBytes: Long = maxFileBytes,
) {
    init {
        dir.mkdirs()
        require(dir.isDirectory) { "not a directory: $dir" }
    }

    private fun part(id: String) = File(dir, "$id.part")

    /** Sum of complete files plus the full declared size of every open upload. */
    @Synchronized
    fun committedBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * Atomically checks the storage cap and reserves [size] bytes for upload [id].
     * Returns false when the relay is full.
     */
    @Synchronized
    fun create(id: String, size: Long): Boolean {
        require(size in 0..maxUploadBytes) { "bad_size" }
        if (committedBytes() + size > maxTotalBytes) return false
        RandomAccessFile(part(id), "rw").use { it.setLength(size) }
        return true
    }

    /**
     * Writes exactly [length] bytes from [body] at [start] of upload [id] and returns the new offset.
     * Throws [IllegalArgumentException] if the body is shorter or longer than [length].
     */
    suspend fun append(id: String, start: Long, body: ByteReadChannel, length: Long): Long {
        val buf = ByteArray(64 * 1024)
        RandomAccessFile(part(id).also { require(it.exists()) { "not_found" } }, "rw").use { raf ->
            val ch = raf.channel
            var pos = start
            var remaining = length
            while (remaining > 0) {
                val n = body.readAvailable(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n == -1) throw IllegalArgumentException("short_body")
                if (n == 0) continue
                val bb = ByteBuffer.wrap(buf, 0, n)
                withContext(Dispatchers.IO) { while (bb.hasRemaining()) pos += ch.write(bb, pos) }
                remaining -= n
            }
            if (body.readAvailable(buf, 0, 1) != -1) throw IllegalArgumentException("long_body")
            withContext(Dispatchers.IO) { ch.force(false) }
            return pos
        }
    }

    /** Renames the part to the final file and returns its SHA-256 hex, computed by reading it back. */
    fun complete(id: String): String {
        val target = file(id)
        Files.move(part(id).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        target.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun file(id: String): File = File(dir, id)

    fun delete(id: String) {
        file(id).delete()
        part(id).delete()
    }
}
