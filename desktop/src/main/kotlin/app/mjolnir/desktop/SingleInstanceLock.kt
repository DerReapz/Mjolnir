package app.mjolnir.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

object SingleInstanceLock {
    /** Holds an exclusive lock on `dir/.lock` until the result is closed; null when another holder exists. */
    fun tryAcquire(dir: File): AutoCloseable? {
        dir.mkdirs()
        val raf = RandomAccessFile(File(dir, ".lock"), "rw")
        val lock = try {
            raf.channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            null
        } catch (e: Exception) {
            runCatching { raf.close() }
            throw e
        }
        if (lock == null) {
            runCatching { raf.close() }
            return null
        }
        return AutoCloseable {
            runCatching { lock.release() }
            runCatching { raf.close() }
        }
    }
}
