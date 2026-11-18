package app.mjolnir.server

import app.mjolnir.protocol.Limits
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File

private const val MIB = 1_048_576L

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val db = System.getenv("MJOLNIR_DB") ?: "./mjolnir.db"
    val filesDir = System.getenv("MJOLNIR_FILES") ?: "./files"
    val maxFileMb = System.getenv("MJOLNIR_MAX_FILE_MB")?.toLongOrNull() ?: 100L
    val maxStorageMb = System.getenv("MJOLNIR_MAX_STORAGE_MB")?.toLongOrNull() ?: 2048L
    val files = FileStorage(
        dir = File(filesDir),
        // The env var can only lower the protocol cap, never raise it.
        maxFileBytes = (maxFileMb * MIB).coerceIn(0L, Limits.MAX_FILE_BYTES),
        maxTotalBytes = maxStorageMb * MIB,
    )
    val deps = RelayDeps(store = Store("jdbc:sqlite:$db"), files = files)
    embeddedServer(Netty, port = port, host = "0.0.0.0") { relayModule(deps) }.start(wait = true)
}
