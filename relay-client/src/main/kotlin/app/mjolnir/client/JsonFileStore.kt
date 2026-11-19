package app.mjolnir.client

import app.mjolnir.protocol.MjolnirJson
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer

/**
 * Small JSON-backed value on disk. A missing file or corrupt content reads as [default]; any other read error
 * (an IOException) propagates, so a transient failure never lets [update] overwrite real data. Writes are atomic.
 */
class JsonFileStore<T>(private val file: File, private val serializer: KSerializer<T>, private val default: () -> T) {
    private val lock = Any()

    /** Replaces the target with the temp file; swappable so tests can simulate a locked file. */
    internal var move: (Path, Path) -> Unit = ::atomicReplace
    internal var moveRetryDelayMs: Long = 50

    fun read(): T = synchronized(lock) { readLocked() }

    fun write(value: T) = synchronized(lock) { writeLocked(value) }

    fun update(transform: (T) -> T): T = synchronized(lock) {
        transform(readLocked()).also { writeLocked(it) }
    }

    private fun readLocked(): T {
        if (!file.exists()) return default()
        val text = file.readText() // IOException propagates
        return try {
            MjolnirJson.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) { // includes SerializationException: corrupt content
            default()
        }
    }

    private fun writeLocked(value: T) {
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(MjolnirJson.encodeToString(serializer, value))
        // On Windows an antivirus scanner or the indexer briefly holding the file makes the rename fail: retry a little.
        var attempt = 1
        while (true) {
            try {
                move(tmp.toPath(), file.toPath())
                return
            } catch (e: AccessDeniedException) {
                if (attempt >= MOVE_ATTEMPTS) throw e
                attempt++
                Thread.sleep(moveRetryDelayMs)
            }
        }
    }

    private companion object {
        const val MOVE_ATTEMPTS = 5

        fun atomicReplace(from: Path, to: Path) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
