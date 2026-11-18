package app.mjolnir.client

import app.mjolnir.protocol.MjolnirJson
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer

/**
 * Small JSON-backed value on disk. A missing file or corrupt content reads as [default]; any other read error
 * (an IOException) propagates, so a transient failure never lets [update] overwrite real data. Writes are atomic.
 */
class JsonFileStore<T>(private val file: File, private val serializer: KSerializer<T>, private val default: () -> T) {
    private val lock = Any()

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
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
