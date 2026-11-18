package app.mjolnir.android

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import app.mjolnir.BuildConfig
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.android.data.TrayItem
import app.mjolnir.client.FileSeenDeliveries
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Outbox
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayClient
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer

class MjolnirApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir

    val config = JsonFileStore(File(filesDir, "config.json"), serializer<PhoneConfig>()) {
        PhoneConfig(relayUrl = BuildConfig.RELAY_URL, deviceName = Build.MODEL)
    }
    val tray = JsonFileStore(File(filesDir, "tray.json"), ListSerializer(serializer<TrayItem>())) { emptyList() }
    val outboxDir = File(filesDir, "outbox")
    val seen = FileSeenDeliveries(File(filesDir, "seen.json"))

    // `caught` (received-items history) is added by the receive task.

    /** Talks to the relay at the current `relayUrl`; the client is rebuilt when the URL changes and reads the token live. */
    val api: RelayApi = switchingApi()

    val outbox = Outbox(
        store = JsonFileStore(File(filesDir, "outbox.json"), ListSerializer(serializer<OutgoingFiles>())) { emptyList() },
        api = { api },
        open = ::openUpload,
        cleanup = ::cleanupUpload,
    )

    private fun openUpload(upload: LocalUpload, offset: Long): InputStream {
        if (upload.path.startsWith("content://")) {
            val input = appContext.contentResolver.openInputStream(Uri.parse(upload.path))
                ?: throw java.io.FileNotFoundException(upload.path)
            try {
                var left = offset
                while (left > 0) {
                    val skipped = input.skip(left)
                    if (skipped <= 0) { // skip() may refuse; fall back to reading
                        if (input.read() < 0) break
                        left--
                    } else left -= skipped
                }
            } catch (e: Exception) {
                input.close()
                throw e
            }
            return input
        }
        return FileInputStream(upload.path).also { input ->
            try {
                input.channel.position(offset)
            } catch (e: Exception) {
                input.close()
                throw e
            }
        }
    }

    /** Deletes the app's own copy; a user's content:// file or any path outside the outbox directory is never touched. */
    private fun cleanupUpload(upload: LocalUpload) {
        if (upload.path.startsWith("content://")) return
        val file = File(upload.path)
        val dir = outboxDir.canonicalFile
        val parent = file.canonicalFile.parentFile
        if (parent != null && (parent == dir || parent.path.startsWith(dir.path + File.separator))) file.delete()
    }

    private fun switchingApi(): RelayApi {
        val lock = Any()
        var url: String? = null
        var client: RelayClient? = null
        fun current(): RelayClient = synchronized(lock) {
            val wanted = config.read().relayUrl
            if (client == null || url != wanted) {
                client?.close()
                client = RelayClient.create(wanted) { config.read().token }
                url = wanted
            }
            client!!
        }
        val handler = InvocationHandler { _, method, args ->
            try {
                method.invoke(current(), *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        return Proxy.newProxyInstance(RelayApi::class.java.classLoader, arrayOf(RelayApi::class.java), handler) as RelayApi
    }
}
