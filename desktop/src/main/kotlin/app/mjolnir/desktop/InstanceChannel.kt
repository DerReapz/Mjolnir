package app.mjolnir.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Semaphore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface Startup {
    /**
     * This process is the running instance. [lock] holds the single-instance lock and the loopback channel: keep it
     * for the process lifetime. [incoming] emits the launch arguments (if any), then the paths of each later launch.
     */
    class Primary(
        val lock: AutoCloseable,
        val incoming: Flow<List<String>>,
        /** Where the hand-off channel listens (loopback), or null when it could not be opened. */
        internal val boundAddress: InetAddress? = null,
    ) : Startup

    /** Another instance is running and accepted our paths. */
    data object Forwarded : Startup

    /** Another instance holds the lock but could not be reached. */
    data object Failed : Startup
}

@Serializable
internal data class IpcInfo(val port: Int, val token: String)

@Serializable
internal data class IpcRequest(val token: String, val paths: List<String>)

/**
 * Hands a second launch's arguments (Explorer *Send to*) to the running instance over a loopback socket.
 * The port and a random token live in `dir/ipc.json`, readable only by the user.
 */
object InstanceChannel {
    const val FILE_NAME = "ipc.json"
    internal const val MAX_LINE_BYTES = 1 shl 20
    internal const val MAX_PATHS = 10_000
    private const val TIMEOUT_MS = 2_000
    private const val MAX_CONNECTIONS = 8
    private const val RETRY_FOR_MS = 2_000L
    private const val RETRY_EVERY_MS = 100L
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * While another process holds the lock, keeps trying for up to [RETRY_FOR_MS]: the holder may still be writing
     * ipc.json (it has just started) or may be exiting (a stale ipc.json). Each attempt first tries the lock again
     * (the old holder is gone: become Primary with these args), then reads ipc.json and hands the args over.
     */
    fun start(dir: File, args: List<String>): Startup {
        val deadline = System.nanoTime() + RETRY_FOR_MS * 1_000_000L
        while (true) {
            SingleInstanceLock.tryAcquire(dir)?.let { return startPrimary(dir, it, args) }
            if (forward(dir, args)) return Startup.Forwarded
            if (System.nanoTime() - deadline >= 0) return Startup.Failed
            Thread.sleep(RETRY_EVERY_MS)
        }
    }

    internal fun startPrimary(dir: File, lock: AutoCloseable, args: List<String>): Startup.Primary {
        val channel = Channel<List<String>>(Channel.UNLIMITED)
        if (args.isNotEmpty()) channel.trySend(args)
        val ipcFile = File(dir, FILE_NAME)
        val server = try {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            System.err.println("Mjolnir: cannot open the instance channel; later launches cannot hand over paths: $e")
            null
        }
        if (server != null) {
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val written = runCatching { writePrivate(ipcFile, json.encodeToString(IpcInfo.serializer(), IpcInfo(server.localPort, token))) }
            if (written.isSuccess) {
                Thread({ acceptLoop(server, token.toByteArray(), channel) }, "mjolnir-instance-channel").apply { isDaemon = true }.start()
            } else {
                System.err.println("Mjolnir: cannot write ${ipcFile.path}; later launches cannot hand over paths: ${written.exceptionOrNull()}")
                runCatching { server.close() }
            }
        }
        val address = server?.takeUnless { it.isClosed }?.inetAddress
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val closeable = AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            runCatching { server?.close() }
            runCatching { ipcFile.delete() }
            channel.close()
            lock.close()
        }
        return Startup.Primary(closeable, channel.receiveAsFlow(), address)
    }

    private fun acceptLoop(server: ServerSocket, token: ByteArray, channel: Channel<List<String>>) {
        val slots = Semaphore(MAX_CONNECTIONS)
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (server.isClosed) return
                Thread.sleep(100) // a persistent accept failure must not spin
                continue
            }
            if (!slots.tryAcquire()) { runCatching { socket.close() }; continue }
            Thread({
                try { socket.use { handle(it, token, channel) } } finally { slots.release() }
            }, "mjolnir-instance-request").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket, token: ByteArray, channel: Channel<List<String>>) {
        try {
            socket.soTimeout = TIMEOUT_MS
            val line = readLine(socket.getInputStream(), MAX_LINE_BYTES) ?: return
            val request = runCatching { json.decodeFromString(IpcRequest.serializer(), line) }.getOrNull()
            val reply = when {
                request == null || !MessageDigest.isEqual(request.token.toByteArray(), token) -> "denied"
                request.paths.size > MAX_PATHS -> "denied"
                channel.trySend(request.paths).isSuccess -> "ok"
                else -> "denied"
            }
            socket.getOutputStream().apply { write("$reply\n".toByteArray()); flush() }
        } catch (e: Exception) {
            // A stuck or broken client: drop it.
        }
    }

    /** Asks the running instance to take [args]; true when it answered "ok". */
    private fun forward(dir: File, args: List<String>): Boolean = try {
        val info = json.decodeFromString(IpcInfo.serializer(), File(dir, FILE_NAME).readText())
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), info.port), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            val line = json.encodeToString(IpcRequest.serializer(), IpcRequest(info.token, args)) + "\n"
            s.getOutputStream().apply { write(line.toByteArray()); flush() }
            readLine(s.getInputStream(), 64) == "ok"
        }
    } catch (e: Exception) {
        false
    }

    /**
     * One UTF-8 line without its terminator; null at end of stream, when longer than [max] bytes, or after [TIMEOUT_MS]
     * in total (so a client dripping one byte at a time cannot hold a connection slot).
     */
    private fun readLine(input: InputStream, max: Int): String? {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
        val buf = ByteArrayOutputStream()
        val bytes = input.buffered()
        while (true) {
            if (System.nanoTime() - deadline > 0) return null
            val b = bytes.read()
            if (b == -1) return if (buf.size() > 0) buf.toString(Charsets.UTF_8) else null
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8).removeSuffix("\r")
            if (buf.size() >= max) return null
            buf.write(b)
        }
    }

    /** Writes [text] to [target] via a temp file that only the owner can read (POSIX); %APPDATA% is per-user on Windows. */
    private fun writePrivate(target: File, text: String) {
        val dir = target.absoluteFile.parentFile.toPath()
        val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
        val tmp = if (posix) {
            Files.createTempFile(dir, "ipc", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createTempFile(dir, "ipc", ".tmp")
        }
        try {
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
