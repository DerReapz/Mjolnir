package app.mjolnir.client

import app.mjolnir.protocol.MjolnirJson
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer

/** Small JSON-backed value on disk. Missing or corrupt files read as [default]; writes are atomic. */
class JsonFileStore<T>(private val file: File, private val serializer: KSerializer<T>, private val default: () -> T) {
    private val lock = Any()

    fun read(): T = synchronized(lock) { readLocked() }

    fun write(value: T) = synchronized(lock) { writeLocked(value) }

    fun update(transform: (T) -> T): T = synchronized(lock) {
        transform(readLocked()).also { writeLocked(it) }
    }

    private fun readLocked(): T = try {
        if (!file.isFile) default() else MjolnirJson.decodeFromString(serializer, file.readText())
    } catch (e: Exception) {
        default()
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
