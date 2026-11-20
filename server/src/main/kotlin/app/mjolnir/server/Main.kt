package app.mjolnir.server

import app.mjolnir.protocol.Limits
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory
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
    val log = LoggerFactory.getLogger("app.mjolnir.server.Main")
    val push: PushSender = if (!System.getenv("GOOGLE_APPLICATION_CREDENTIALS").isNullOrBlank()) {
        try {
            FirebaseApp.initializeApp()
            FcmPushSender(FirebaseMessaging.getInstance()).also { log.info("FCM enabled") }
        } catch (e: Exception) {
            // A bad or unreadable credentials file must not keep the relay from starting.
            log.warn("FCM disabled (init failed: {})", e.toString())
            NoopPushSender
        }
    } else {
        log.info("FCM disabled (GOOGLE_APPLICATION_CREDENTIALS not set)")
        NoopPushSender
    }
    val defaults = RelayLimits()
    val limits = defaults.copy(
        maxSpacesPerDevice = System.getenv("MJOLNIR_MAX_SPACES")?.toIntOrNull()?.coerceAtLeast(1) ?: defaults.maxSpacesPerDevice,
        maxMembersPerSpace = System.getenv("MJOLNIR_MAX_MEMBERS")?.toIntOrNull()?.coerceAtLeast(2) ?: defaults.maxMembersPerSpace,
    )
    val deps = RelayDeps(store = Store("jdbc:sqlite:$db"), limits = limits, files = files, push = push)
    embeddedServer(Netty, port = port, host = "0.0.0.0") { relayModule(deps) }.start(wait = true)
}
