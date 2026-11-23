@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.InboxDrainer
import app.mjolnir.client.Platform
import app.mjolnir.client.RelayClient
import app.mjolnir.client.UpdateState
import app.mjolnir.client.Updater
import app.mjolnir.client.installMjolnirDefaults
import app.mjolnir.desktop.ui.MjolnirTheme
import app.mjolnir.desktop.ui.UpdateBanner
import app.mjolnir.desktop.ui.trayUpdateLabel
import app.mjolnir.desktop.ui.updateBanner
import app.mjolnir.protocol.RELAY_VERSION_HEADER
import app.mjolnir.protocol.RelayEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir

/** A GitHub stand-in with release `v1.0.9` (MSI + SHA256SUMS); records every request it gets. */
internal class FakeReleases(val msi: ByteArray = ByteArray(50_000) { (it % 251).toByte() }) {
    val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequestData>())
    private val sha = MessageDigest.getInstance("SHA-256").digest(msi).joinToString("") { "%02x".format(it) }
    private fun url(name: String) = "https://github.com/DerReapz/Mjolnir/releases/download/v1.0.9/$name"

    fun client() = HttpClient(MockEngine) {
        installMjolnirDefaults()
        engine {
            addHandler { req ->
                requests += req
                val path = req.url.encodedPath
                when {
                    path.endsWith("/releases/latest") -> respond(
                        """{"tag_name":"v1.0.9","assets":[""" +
                            """{"name":"Mjolnir-1.0.9.msi","size":${msi.size},"browser_download_url":"${url("Mjolnir-1.0.9.msi")}"},""" +
                            """{"name":"SHA256SUMS","size":100,"browser_download_url":"${url("SHA256SUMS")}"}]}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    path.endsWith("/SHA256SUMS") -> respond("$sha  Mjolnir-1.0.9.msi\n")
                    path.endsWith(".msi") -> respond(msi, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, msi.size.toString()))
                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        }
    }
}

/** An updater that never reaches the network: every check answers 404, so it stays Idle. */
internal fun offlineUpdater(dataDir: File, scope: CoroutineScope) = Updater(
    HttpClient(MockEngine) { engine { addHandler { respondError(HttpStatusCode.NotFound) } } },
    Platform.WINDOWS_MSI, "1.0.5", File(dataDir, "updates"), scope,
)

class UpdateFlowTest {
    @TempDir lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest fun tearDown() = scope.cancel()

    private class Launches : InstallerLauncher {
        val files = mutableListOf<File>()
        var fail = false
        override fun launch(file: File) {
            if (fail) throw IOException("msiexec not found")
            files += file
        }
    }

    private fun controller(
        releases: FakeReleases = FakeReleases(),
        launcher: InstallerLauncher = Launches(),
        events: MutableList<String> = mutableListOf(),
        api: (String) -> app.mjolnir.client.RelayApi = { FakeRelayApi() },
        config: String? = null,
    ): AppController {
        config?.let { File(dir, "config.json").writeText(it) }
        return AppController(
            dir, scope,
            newUpdater = { s -> Updater(releases.client(), Platform.WINDOWS_MSI, "1.0.5", File(dir, "updates"), s) },
            installer = { f -> events += "launch"; launcher.launch(f) },
            exitApp = { events += "exit" },
        ) { url, _ -> api(url) }
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test fun bannerShowsAvailable() = runComposeUiTest {
        var installs = 0
        val holder = androidx.compose.runtime.mutableStateOf<UpdateState>(UpdateState.Available("1.0.9"))
        setContent { MjolnirTheme { UpdateBanner(holder.value, relayTooOld = false, onInstall = { installs++ }) } }
        onNodeWithText("Update available: 1.0.9").assertExists()
        onNodeWithText("Install").performClick()
        assertEquals(1, installs)

        holder.value = UpdateState.Downloading("1.0.9", 42)
        onNodeWithText("Downloading update… 42%").assertExists()
        onNodeWithText("Install").assertDoesNotExist()

        holder.value = UpdateState.Failed("1.0.9", "checksum mismatch")
        onNodeWithText("Update failed: checksum mismatch. Try again.").assertExists()
        onNodeWithText("Install").performClick()
        assertEquals(2, installs)

        holder.value = UpdateState.Ready("1.0.9", File("x.msi"))
        onNodeWithText("Installing…").assertExists()
        onNodeWithText("Install").assertDoesNotExist()

        holder.value = UpdateState.Idle
        onNodeWithText("Update", substring = true).assertDoesNotExist()
    }

    @Test fun installDownloadsThenLaunchesThenExits() {
        val releases = FakeReleases()
        val launcher = Launches()
        val events = mutableListOf<String>()
        val c = controller(releases, launcher, events)
        c.start()
        await { c.updateState.first { it is UpdateState.Available } }
        // The GitHub client is the updater's own: no relay bearer token or app headers.
        releases.requests.forEach { assertNull(it.headers[HttpHeaders.Authorization]); assertNull(it.headers["Mjolnir-App-Version"]) }

        await { c.installUpdate().join() }

        val file = launcher.files.single()
        assertEquals(File(File(dir, "updates"), "Mjolnir-1.0.9.msi"), file)
        assertTrue(file.readBytes().contentEquals(releases.msi))
        assertEquals(listOf("launch", "exit"), events)
        assertEquals(listOf("msiexec", "/i", file.absolutePath, "/passive", "/norestart"), msiexecCommand(file))
    }

    @Test fun launchFailureShowsFailed() {
        val launcher = Launches().apply { fail = true }
        val events = mutableListOf<String>()
        val c = controller(launcher = launcher, events = events)
        c.start()
        await { c.updateState.first { it is UpdateState.Available } }
        await { c.installUpdate().join() }

        assertEquals(UpdateState.Failed("1.0.9", INSTALLER_DIDNT_START), c.updateState.value)
        assertEquals(listOf("launch"), events) // the app stays open
        assertEquals("Update failed: the installer didn't start. Try again." to true, updateBanner(c.updateState.value, relayTooOld = false))

        launcher.fail = false // Install again downloads afresh and launches
        await { c.installUpdate().join() }
        assertEquals(listOf("launch", "launch", "exit"), events)
    }

    @Test fun trayItemPresentOnlyWhenAvailable() {
        assertEquals("Update to 1.0.9", trayUpdateLabel(UpdateState.Available("1.0.9")))
        assertNull(trayUpdateLabel(UpdateState.Idle))
        assertNull(trayUpdateLabel(UpdateState.Downloading("1.0.9", 10)))
        assertNull(trayUpdateLabel(UpdateState.Ready("1.0.9", File("x.msi"))))
        assertNull(trayUpdateLabel(UpdateState.Failed("1.0.9", "download failed")))
    }

    @Test fun relayTooOldBanner() {
        // The relay's version header, seen on any relay call, reaches the updater.
        val relayHttp = HttpClient(MockEngine) {
            installMjolnirDefaults()
            engine {
                addHandler { respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), RELAY_VERSION_HEADER to listOf("1.0.3"))) }
            }
        }
        val c = controller(
            api = { url -> RelayClient(relayHttp, url, "1.0.5", token = { "t" }) },
            config = """{"relayUrl":"https://a.example","deviceId":"d","token":"t","deviceName":"PC"}""",
        )
        assertFalse(c.relayTooOld.value)
        await { c.refreshPeers() }
        await { c.relayTooOld.first { it } }

        // It takes the update banner's slot (no button), but not from an update the user already started.
        assertEquals("This version needs a newer relay." to false, updateBanner(UpdateState.Idle, relayTooOld = true))
        assertEquals("This version needs a newer relay." to false, updateBanner(UpdateState.Available("1.0.9"), relayTooOld = true))
        assertEquals("Downloading update… 5%" to false, updateBanner(UpdateState.Downloading("1.0.9", 5), relayTooOld = true))
        assertNull(updateBanner(UpdateState.Idle, relayTooOld = false))
    }

    @Test fun relayTooOldBannerShows() = runComposeUiTest {
        setContent { MjolnirTheme { UpdateBanner(UpdateState.Idle, relayTooOld = true, onInstall = {}) } }
        onNodeWithText("This version needs a newer relay.").assertExists()
        onNodeWithText("Install").assertDoesNotExist()
    }

    @Test fun oldDownloadsCleanedAtStart() {
        val updates = File(dir, "updates").apply { mkdirs() }
        File(updates, "Mjolnir-1.0.4.msi").writeText("old")
        File(updates, "Mjolnir-1.0.6.msi.part").writeText("partial")
        val releases = FakeReleases()
        val c = controller(releases)
        c.start()
        await { while (!updates.listFiles().isNullOrEmpty()) delay(10) }
        await { c.updateState.first { it is UpdateState.Available } } // and the updater was started
        assertTrue(releases.requests.any { it.url.encodedPath.endsWith("/releases/latest") })
    }

    @Test fun updateAvailableEventReachesTheUpdater() {
        val api = FakeRelayApi()
        val got = mutableListOf<String>()
        val svc = CatchService(api, InboxDrainer(api, app.mjolnir.client.MemorySeen()) {}, {}, {}, onUpdateAvailable = { got += it })
        await {
            val job = scope.launch { svc.run() }
            while (api.events.subscriptionCount.value == 0) delay(5)
            api.events.emit(RelayEvent.UpdateAvailable("1.0.9"))
            while (got.isEmpty()) delay(5)
            job.cancel()
        }
        assertEquals(listOf("1.0.9"), got)
    }
}
