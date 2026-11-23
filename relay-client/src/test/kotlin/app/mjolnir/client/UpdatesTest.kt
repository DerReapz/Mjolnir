package app.mjolnir.client

import app.mjolnir.protocol.RELAY_VERSION_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdatesTest {
    private val latestUrl = "https://api.github.com/repos/DerReapz/Mjolnir/releases/latest"
    private val msi = "Mjolnir-1.0.7.msi".toByteArray().let { ByteArray(200_000) { i -> it[i % it.size] } }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun assetUrl(name: String) = "https://github.com/DerReapz/Mjolnir/releases/download/v1.0.7/$name"

    private fun releaseJson(tag: String = "v1.0.7", assets: List<String> = listOf("Mjolnir-1.0.7.msi", "mjolnir-1.0.7.apk", "server-all.jar", "SHA256SUMS")) =
        """{"tag_name":"$tag","name":"Mjolnir $tag","assets":[""" +
            assets.joinToString(",") { """{"name":"$it","size":${msi.size},"browser_download_url":"${assetUrl(it)}","extra":1}""" } +
            "]}"

    private fun sums(msiSha: String = sha(msi)) = "$msiSha  Mjolnir-1.0.7.msi\n${"0".repeat(64)}  mjolnir-1.0.7.apk\n"

    /** A GitHub stand-in: the latest-release JSON, SHA256SUMS and the MSI, each overridable; counts API calls. */
    private class FakeGitHub(
        var latest: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        var sums: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        var msi: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        var apiCalls = 0
        val requests = mutableListOf<HttpRequestData>()

        fun client(dispatcher: CoroutineDispatcher? = null) = HttpClient(MockEngine) {
            engine {
                dispatcher?.let { this.dispatcher = it }
                addHandler { req ->
                    requests += req
                    val path = req.url.encodedPath
                    when {
                        path.endsWith("/releases/latest") -> { apiCalls++; latest(req) }
                        path.endsWith("/SHA256SUMS") -> sums(req)
                        path.endsWith(".msi") -> msi(req)
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            }
        }
    }

    private fun gitHub(release: String = releaseJson(), sumsText: String = sums()) = FakeGitHub(
        latest = { respond(release, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
        sums = { respond(sumsText) },
        msi = { respond(msi, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, msi.size.toString())) },
    )

    private fun tempDir(): File = Files.createTempDirectory("updates").toFile().also { it.deleteOnExit() }

    private fun TestScope.updater(gh: FakeGitHub, dir: File = tempDir(), current: String = "1.0.5", virtual: Boolean = false, platform: Platform = Platform.WINDOWS_MSI) =
        Updater(
            gh.client(if (virtual) StandardTestDispatcher(testScheduler) else null), platform, current, dir, backgroundScope,
            clock = { testScheduler.currentTime },
        )

    /** Waits in real time: downloads run on real IO threads. */
    private suspend fun <T> realTimeout(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5_000) { block() } }

    private suspend fun Updater.awaitState(predicate: (UpdateState) -> Boolean): UpdateState = realTimeout { state.first(predicate) }

    @Test fun newerReleaseIsAvailable() = runTest {
        val gh = gitHub()
        val u = updater(gh, virtual = true)
        u.start(); runCurrent()
        assertEquals(UpdateState.Available("1.0.7"), u.state.value)
        val req = gh.requests.single()
        assertEquals(latestUrl, req.url.toString())
        assertEquals("application/vnd.github+json", req.headers[HttpHeaders.Accept])
        assertEquals(null, req.headers[HttpHeaders.Authorization])
    }

    @Test fun androidPicksTheApk() = runTest {
        val u = updater(gitHub(), virtual = true, platform = Platform.ANDROID_APK)
        u.start(); runCurrent()
        assertEquals(UpdateState.Available("1.0.7"), u.state.value)
    }

    @Test fun sameVersionIsIdle() = runTest {
        val u = updater(gitHub(), current = "1.0.7", virtual = true)
        u.start(); runCurrent()
        assertEquals(UpdateState.Idle, u.state.value)
        val older = updater(gitHub(), current = "1.0.10", virtual = true)
        older.start(); runCurrent()
        assertEquals(UpdateState.Idle, older.state.value)
        val junk = updater(gitHub(release = releaseJson(tag = "nightly")), virtual = true)
        junk.start(); runCurrent()
        assertEquals(UpdateState.Idle, junk.state.value)
    }

    @Test fun downloadVerifiesChecksumThenReady() = runTest {
        val dir = tempDir()
        val u = updater(gitHub(), dir)
        u.onUpdateAvailable("1.0.7")
        u.awaitState { it is UpdateState.Available }
        val seen = mutableListOf<UpdateState>()
        val watcher = backgroundScope.launch { u.state.collect { seen += it } }
        u.download()
        val ready = assertIs<UpdateState.Ready>(u.state.value)
        assertEquals("1.0.7", ready.version)
        assertEquals(File(dir, "Mjolnir-1.0.7.msi"), ready.file)
        assertTrue(ready.file.readBytes().contentEquals(msi))
        assertEquals(listOf("Mjolnir-1.0.7.msi"), dir.list()!!.toList())
        watcher.cancel()
        assertTrue(seen.any { it is UpdateState.Downloading && it.version == "1.0.7" })
    }

    @Test fun checksumMismatchFailsAndDeletes() = runTest {
        val dir = tempDir()
        val u = updater(gitHub(sumsText = sums(msiSha = "a".repeat(64))), dir)
        u.onUpdateAvailable("1.0.7")
        u.awaitState { it is UpdateState.Available }
        u.download()
        assertEquals(UpdateState.Failed("1.0.7", "checksum mismatch"), u.state.value)
        assertEquals(emptyList(), dir.list()!!.toList())
        assertEquals("Update failed: checksum mismatch. Try again.", Messages.updateFailed("checksum mismatch"))
    }

    @Test fun rateLimitedCheckStaysIdle() = runTest {
        for (code in listOf(HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests)) {
            val gh = gitHub().apply { latest = { respond("""{"message":"API rate limit exceeded"}""", code) } }
            val u = updater(gh, virtual = true)
            u.start(); runCurrent()
            assertEquals(UpdateState.Idle, u.state.value)
            // The next interval tries again and picks the release up.
            gh.latest = { respond(releaseJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            advanceTimeBy(Updater.CHECK_INTERVAL_MS); runCurrent()
            assertEquals(UpdateState.Available("1.0.7"), u.state.value)
        }
        // Offline: the engine throws.
        val offline = gitHub().apply { latest = { throw java.io.IOException("offline") } }
        val u = updater(offline, virtual = true)
        u.start(); runCurrent()
        assertEquals(UpdateState.Idle, u.state.value)
    }

    @Test fun missingAssetIsNotAvailable() = runTest {
        val noMsi = updater(gitHub(release = releaseJson(assets = listOf("mjolnir-1.0.7.apk", "SHA256SUMS"))), virtual = true)
        noMsi.start(); runCurrent()
        assertEquals(UpdateState.Idle, noMsi.state.value)
        val noSums = updater(gitHub(release = releaseJson(assets = listOf("Mjolnir-1.0.7.msi", "mjolnir-1.0.7.apk"))), virtual = true)
        noSums.start(); runCurrent()
        assertEquals(UpdateState.Idle, noSums.state.value)
        // The APK is there, so the phone does see it.
        val phone = updater(gitHub(release = releaseJson(assets = listOf("mjolnir-1.0.7.apk", "SHA256SUMS"))), virtual = true, platform = Platform.ANDROID_APK)
        phone.start(); runCurrent()
        assertEquals(UpdateState.Available("1.0.7"), phone.state.value)
    }

    @Test fun appNewerThanRelayFlaggedOnce() = runTest {
        val u = updater(gitHub(), current = "1.0.10", virtual = true)
        val flips = mutableListOf<Boolean>()
        backgroundScope.launch { u.relayTooOld.collect { flips += it } }
        runCurrent()
        u.onRelayVersion("1.0.10"); u.onRelayVersion("1.0.11"); runCurrent()
        assertFalse(u.relayTooOld.value)
        u.onRelayVersion("1.0.9"); u.onRelayVersion("1.0.9"); u.onRelayVersion("1.0.2"); runCurrent()
        assertTrue(u.relayTooOld.value)
        u.onRelayVersion("1.0.12"); u.onRelayVersion("garbage"); runCurrent() // never cleared within a session
        assertTrue(u.relayTooOld.value)
        assertEquals(listOf(false, true), flips)
        assertEquals("This version needs a newer relay.", Messages.RELAY_TOO_OLD)
    }

    @Test fun interruptedDownloadLeavesNoFile() = runTest {
        val dir = tempDir()
        val halfWritten = CompletableDeferred<Unit>()
        val gh = gitHub().apply {
            msi = {
                val body = backgroundScope.writer {
                    channel.writeFully(this@UpdatesTest.msi, 0, this@UpdatesTest.msi.size / 2); channel.flush()
                    halfWritten.complete(Unit)
                    awaitCancellation()
                }.channel
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, this@UpdatesTest.msi.size.toString()))
            }
        }
        val u = updater(gh, dir)
        u.onUpdateAvailable("1.0.7")
        u.awaitState { it is UpdateState.Available }
        val job = launch { u.download() }
        realTimeout { halfWritten.await() }
        u.awaitState { it is UpdateState.Downloading && it.percent > 0 }
        job.cancel(); job.join()
        assertEquals(emptyList(), dir.list()!!.toList())
        assertEquals(UpdateState.Available("1.0.7"), u.state.value) // Install can be tapped again

        // A connection that breaks mid-file fails the same way, also leaving nothing behind.
        gh.msi = {
            val body = backgroundScope.writer {
                channel.writeFully(this@UpdatesTest.msi, 0, 1000); channel.flush()
                throw java.io.IOException("connection reset")
            }.channel
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, this@UpdatesTest.msi.size.toString()))
        }
        u.download()
        assertIs<UpdateState.Failed>(u.state.value)
        assertEquals(emptyList(), dir.list()!!.toList())

        // And a retry after the failure succeeds.
        gh.msi = { respond(this@UpdatesTest.msi, HttpStatusCode.OK) }
        u.download()
        assertIs<UpdateState.Ready>(u.state.value)
    }

    @Test fun updateAvailableEventTriggersCheck() = runTest {
        val gh = gitHub().apply { latest = { respond("", HttpStatusCode.TooManyRequests) } }
        val u = updater(gh, virtual = true)
        u.start(); runCurrent()
        assertEquals(1, gh.apiCalls)
        gh.latest = { respond(releaseJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        advanceTimeBy(60_000)
        u.onUpdateAvailable("1.0.7"); runCurrent()
        assertEquals(2, gh.apiCalls)
        assertEquals(UpdateState.Available("1.0.7"), u.state.value)
        // Already showing that version: a reconnect's repeated push does not hit the API again.
        u.onUpdateAvailable("1.0.7"); runCurrent()
        assertEquals(2, gh.apiCalls)
    }

    @Test fun sixHourRecheck() = runTest {
        val gh = gitHub(release = releaseJson(tag = "v1.0.5"))
        val u = updater(gh, virtual = true)
        u.start(); runCurrent()
        assertEquals(1, gh.apiCalls)
        advanceTimeBy(Updater.CHECK_INTERVAL_MS - 1); runCurrent()
        assertEquals(1, gh.apiCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, gh.apiCalls)
        advanceTimeBy(Updater.CHECK_INTERVAL_MS); runCurrent()
        assertEquals(3, gh.apiCalls)
        assertEquals(6 * 60 * 60 * 1000L, Updater.CHECK_INTERVAL_MS)
        u.start(); runCurrent() // a second start does not add a second schedule
        advanceTimeBy(Updater.CHECK_INTERVAL_MS); runCurrent()
        assertEquals(4, gh.apiCalls)
    }

    @Test fun cleanOldKeepsOnlyTheReadyFile() = runTest {
        val dir = tempDir()
        File(dir, "Mjolnir-1.0.3.msi").writeText("old")
        File(dir, "Mjolnir-1.0.4.msi.part").writeText("partial")
        val u = updater(gitHub(), dir)
        u.cleanOld()
        assertEquals(emptyList(), dir.list()!!.toList())
        File(dir, "Mjolnir-1.0.3.msi").writeText("old")
        u.onUpdateAvailable("1.0.7"); u.awaitState { it is UpdateState.Available }
        u.download()
        u.cleanOld()
        assertEquals(listOf("Mjolnir-1.0.7.msi"), dir.list()!!.toList())
    }

    @Test fun bareNotFoundIsRelayOutdated() = runTest {
        val http = HttpClient(MockEngine) {
            installMjolnirDefaults()
            engine {
                addHandler { req ->
                    if (req.url.encodedPath == "/v1/spaces/gone/chats") {
                        respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        respond("", HttpStatusCode.NotFound, headersOf(RELAY_VERSION_HEADER, "1.0.3"))
                    }
                }
            }
        }
        val client = RelayClient(http, "http://relay.test", "1.0.5", token = { "t" })
        val e = assertFailsWith<RelayException> { client.chats("s1") }
        assertEquals(RelayErrorKind.RELAY_OUTDATED, e.kind)
        assertEquals("The relay needs updating. Ask whoever runs it to update.", Messages.chatError(e))
        assertEquals(Messages.RELAY_OUTDATED, spaceErrorCopy(e))
        assertEquals("1.0.3", client.relayVersion.value)
        // The relay's own 404 (with its JSON error) is still an ordinary not-found.
        val gone = assertFailsWith<RelayException> { client.chats("gone") }
        assertEquals(RelayErrorKind.NOT_FOUND, gone.kind)
        assertEquals("That chat is no longer there.", Messages.chatError(gone))
    }

    @Test fun relayOutdatedCopyInEveryMapper() {
        val e = RelayException(RelayErrorKind.RELAY_OUTDATED, "HTTP 404")
        assertEquals(Messages.RELAY_OUTDATED, Messages.chatError(e))
        assertEquals(Messages.RELAY_OUTDATED, Messages.mediaNotSent(e))
        assertEquals("the relay needs updating", Messages.failureReason(RelayErrorKind.RELAY_OUTDATED, "PC"))
        assertEquals(Messages.RELAY_OUTDATED, spaceErrorCopy(e))
    }

    @Test fun claimOnAnOutdatedRelaySaysSo() = runTest {
        val api = FakeRelayApi().apply { onClaim = { throw RelayException(RelayErrorKind.RELAY_OUTDATED, "HTTP 404") } }
        assertEquals(ClaimOutcome.Failed(Messages.RELAY_OUTDATED), claimAndAwait(api, "ABC234", {}))
    }

    @Test fun updateCopy() {
        assertEquals("Update available: 1.0.7", Messages.updateAvailable("1.0.7"))
        assertEquals("Install", Messages.INSTALL)
        assertEquals("Downloading update… 42%", Messages.downloadingUpdate(42))
        assertEquals("Installing…", Messages.INSTALLING)
    }
}
