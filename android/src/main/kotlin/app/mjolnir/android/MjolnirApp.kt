package app.mjolnir.android

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import app.mjolnir.BuildConfig
import androidx.work.ExistingWorkPolicy
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.android.data.TrayItem
import app.mjolnir.android.send.createSendingChannels
import app.mjolnir.android.send.scheduleOutgoing
import app.mjolnir.android.session.PhoneSession
import app.mjolnir.android.session.SwitchingRelayApi
import app.mjolnir.client.FileSeenDeliveries
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Outbox
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer

class MjolnirApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        createSendingChannels(this)
        // Throws still in the outbox (e.g. after a force-stop or reboot) get their work back; KEEP leaves running
        // or waiting work alone.
        Thread {
            try {
                for (item in container.outbox.pending()) scheduleOutgoing(this, item.id, policy = ExistingWorkPolicy.KEEP)
            } catch (e: IOException) {
                // The outbox couldn't be read; the next start tries again.
            }
        }.start()
    }
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
    val api: RelayApi = SwitchingRelayApi(::currentClient)

    /** The one registration guard for the whole app. */
    val session = PhoneSession(api, config)

    /** Call after the relay URL was written to [config]; the next call builds a client for the new address. */
    fun invalidateRelayClient() = synchronized(clientLock) { cachedUrl = null }

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

    private val clientLock = Any()
    private var cachedUrl: String? = null
    private var client: RelayClient? = null

    /** Cached so calls never read config.json; rebuilt (old client closed) after [invalidateRelayClient]. */
    private fun currentClient(): RelayApi = synchronized(clientLock) {
        val existing = client
        if (existing != null && cachedUrl != null) return existing
        val url = config.read().relayUrl
        existing?.close()
        RelayClient.create(url) { config.read().token }.also { client = it; cachedUrl = url }
    }
}
