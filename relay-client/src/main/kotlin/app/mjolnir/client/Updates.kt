package app.mjolnir.client

import app.mjolnir.protocol.Versions
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Which release asset this app installs. */
enum class Platform { WINDOWS_MSI, ANDROID_APK }

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val version: String) : UpdateState
    data class Downloading(val version: String, val percent: Int) : UpdateState
    /** [file] has passed its SHA-256 check and can be handed to the installer. */
    data class Ready(val version: String, val file: File) : UpdateState
    /** [reason] is a lowercase fragment for [Messages.updateFailed], e.g. "checksum mismatch". */
    data class Failed(val version: String, val reason: String) : UpdateState
}

/**
 * Finds, downloads and verifies app updates from the project's latest GitHub release. Never installs: the app hands a
 * [UpdateState.Ready] file to its installer when the user taps Install.
 *
 * [http] is a client of its own (not a [RelayClient]'s, which adds relay headers and is closed with it); the apps pass
 * [newMjolnirHttpClient]. A failed check (offline, rate limit) changes nothing and is retried at the next interval.
 */
class Updater(
    private val http: HttpClient,
    private val platform: Platform,
    private val currentVersion: String,
    private val downloadDir: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _relayTooOld = MutableStateFlow(false)
    /** True while the current relay's version is lower than this app's; follows the relay (updated or switched: cleared). */
    val relayTooOld: StateFlow<Boolean> = _relayTooOld.asStateFlow()

    /** The release behind the current [UpdateState.Available] (and any download of it). */
    @Volatile private var release: FoundRelease? = null
    private var schedule: Job? = null
    private val checkLock = Mutex()
    private val downloadLock = Mutex()
    @Volatile private var lastPushCheckAt: Long? = null

    /**
     * Checks now (or, with [checkNow] false, first after one interval), then every [CHECK_INTERVAL_MS]. A second call
     * does nothing.
     */
    fun start(checkNow: Boolean = true) {
        synchronized(this) {
            if (schedule?.isActive == true) return
            schedule = scope.launch {
                if (!checkNow) delay(CHECK_INTERVAL_MS)
                while (true) {
                    check()
                    delay(CHECK_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * The relay pushed `UpdateAvailable`: checks now, unless [version] (or newer) is already shown or a push-triggered
     * check ran in the last [PUSH_CHECK_GAP_MS] (the relay pushes on every WebSocket connect, so a flapping link would
     * otherwise spend GitHub's 60 requests an hour).
     */
    fun onUpdateAvailable(version: String) {
        val shown = when (val s = _state.value) {
            is UpdateState.Available -> s.version
            is UpdateState.Downloading -> s.version
            is UpdateState.Ready -> s.version
            else -> null
        }
        if (shown != null && !Versions.isNewer(version, shown)) return
        val now = clock()
        lastPushCheckAt?.let { if (now - it < PUSH_CHECK_GAP_MS) return }
        lastPushCheckAt = now
        scope.launch { check() }
    }

    /**
     * The relay's version from a response header. [relayTooOld] follows it (a relay older than this app sets it, a
     * current one clears it), and a relay newer than this app means a release is out: [onUpdateAvailable] checks for it
     * (throttled as a push). An unreadable version changes nothing.
     */
    fun onRelayVersion(version: String) {
        Versions.parse(version) ?: return
        _relayTooOld.value = Versions.isNewer(currentVersion, version)
        if (Versions.isNewer(version, currentVersion)) onUpdateAvailable(version)
    }

    /**
     * Downloads the [UpdateState.Available] (or [UpdateState.Failed], to try again) release: `Downloading` with progress,
     * then `Ready` once the file matches its SHA256SUMS line, else `Failed` with nothing left on disk. Writes
     * `<name>.part` and renames it only after the check. Cancelled, it deletes the partial file and goes back to
     * `Available`. Does nothing in any other state or while a download runs.
     */
    suspend fun download() {
        if (!downloadLock.tryLock()) return
        try {
            val version = when (val s = _state.value) {
                is UpdateState.Available -> s.version
                is UpdateState.Failed -> s.version
                else -> return
            }
            val found = release?.takeIf { it.version == version } ?: return
            _state.value = UpdateState.Downloading(version, 0)
            val part = File(downloadDir, found.assetName + ".part")
            _state.value = try {
                fetchVerified(found, part)
            } catch (e: CancellationException) {
                part.delete()
                File(downloadDir, found.assetName).delete() // cancelled just after the rename: not handed out, so not kept
                _state.value = UpdateState.Available(version)
                throw e
            } catch (e: Exception) {
                part.delete()
                UpdateState.Failed(version, (e as? UpdateFailure)?.reason ?: DOWNLOAD_FAILED)
            }
        } finally {
            downloadLock.unlock()
        }
    }

    /**
     * The installer could not start the [UpdateState.Ready] file: shows [reason] as `Failed`; [download] then fetches it
     * again. Does nothing in any other state.
     */
    fun installFailed(reason: String) {
        _state.update { s -> if (s is UpdateState.Ready) UpdateState.Failed(s.version, reason) else s }
    }

    /** Deletes everything in the download folder except the file of the current [UpdateState.Ready]. */
    fun cleanOld() {
        val keep = (_state.value as? UpdateState.Ready)?.file?.absoluteFile
        downloadDir.listFiles()?.forEach { f -> if (f.absoluteFile != keep) f.deleteRecursively() }
    }

    private suspend fun fetchVerified(found: FoundRelease, part: File): UpdateState = withContext(Dispatchers.IO) {
        val expected = expectedSha256(found) ?: throw UpdateFailure("checksum missing")
        downloadDir.mkdirs()
        part.delete()
        val md = MessageDigest.getInstance("SHA-256")
        http.prepareGet(found.assetUrl) { transferTimeout() }.execute { response ->
            if (response.status.value !in 200..299) throw UpdateFailure(DOWNLOAD_FAILED)
            val total = response.contentLength()?.takeIf { it > 0 } ?: found.size.takeIf { it > 0 }
            val channel = response.bodyAsChannel()
            FileOutputStream(part).use { out ->
                val buf = ByteArray(BUFFER_BYTES)
                var have = 0L
                var shown = 0
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    have += n
                    if (total != null) {
                        val percent = (have * 100 / total).toInt().coerceIn(0, 99)
                        if (percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(found.version, percent)
                        }
                    }
                }
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) throw UpdateFailure(CHECKSUM_MISMATCH)
        val target = File(downloadDir, found.assetName)
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        UpdateState.Ready(found.version, target)
    }

    /** The asset's hash from the release's SHA256SUMS (`<64 hex>  <name>`, `*<name>` also accepted). */
    private suspend fun expectedSha256(found: FoundRelease): String? {
        val response = http.get(found.sumsUrl) { transferTimeout() }
        if (response.status.value !in 200..299) throw UpdateFailure(DOWNLOAD_FAILED)
        return response.bodyAsText().lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == found.assetName && SHA256.matches(it[0]) }
            ?.get(0)
    }

    private suspend fun check() = checkLock.withLock {
        val found = try {
            latestRelease()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock // offline, rate-limited or unreadable: stay as we are and retry at the next interval
        } ?: return@withLock
        if (!Versions.isNewer(found.version, currentVersion)) return@withLock
        _state.update { s ->
            val busyWith = when (s) {
                is UpdateState.Downloading -> s.version
                is UpdateState.Ready -> s.version
                else -> null
            }
            if (busyWith != null && !Versions.isNewer(found.version, busyWith)) {
                s
            } else if (s is UpdateState.Available && s.version == found.version || s is UpdateState.Failed && s.version == found.version) {
                release = found
                s
            } else {
                release = found
                UpdateState.Available(found.version)
            }
        }
    }

    /** The latest release if it carries this platform's asset and SHA256SUMS; null when it doesn't or can't be read. */
    private suspend fun latestRelease(): FoundRelease? {
        val response = http.get(LATEST_RELEASE_URL) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "Mjolnir/$currentVersion")
        }
        if (response.status.value !in 200..299) return null
        val release = json.decodeFromString(GitHubRelease.serializer(), response.bodyAsText())
        if (!release.tagName.startsWith("v")) return null
        val version = release.tagName.removePrefix("v")
        if (Versions.parse(version) == null) return null
        val assetName = assetName(platform, version)
        val asset = release.assets.firstOrNull { it.name == assetName } ?: return null
        val sums = release.assets.firstOrNull { it.name == SUMS_ASSET } ?: return null
        return FoundRelease(version, assetName, asset.url, asset.size, sums.url)
    }

    /** No overall deadline for release files (slow links), but a stalled socket still fails; only with [HttpTimeout] installed. */
    private fun HttpRequestBuilder.transferTimeout() {
        if (http.pluginOrNull(HttpTimeout) == null) return
        timeout {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = TRANSFER_SOCKET_TIMEOUT_MS
        }
    }

    private class UpdateFailure(val reason: String) : Exception(reason)

    private data class FoundRelease(val version: String, val assetName: String, val assetUrl: String, val size: Long, val sumsUrl: String)

    @Serializable
    private data class GitHubRelease(@SerialName("tag_name") val tagName: String, val assets: List<GitHubAsset> = emptyList())

    @Serializable
    private data class GitHubAsset(val name: String, val size: Long = 0, @SerialName("browser_download_url") val url: String)

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/DerReapz/Mjolnir/releases/latest"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val PUSH_CHECK_GAP_MS = 60_000L
        const val CHECKSUM_MISMATCH = "checksum mismatch"
        const val DOWNLOAD_FAILED = "download failed"
        private const val SUMS_ASSET = "SHA256SUMS"
        private const val TRANSFER_SOCKET_TIMEOUT_MS = 60_000L
        private const val BUFFER_BYTES = 64 * 1024
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val json = Json { ignoreUnknownKeys = true }

        /** The release asset for [platform] at [version]; the release's tag is `v<version>`. */
        fun assetName(platform: Platform, version: String): String = when (platform) {
            Platform.WINDOWS_MSI -> "Mjolnir-$version.msi"
            Platform.ANDROID_APK -> "mjolnir-$version.apk"
        }
    }
}
