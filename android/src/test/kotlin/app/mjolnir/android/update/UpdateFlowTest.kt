package app.mjolnir.android.update

import android.content.pm.PackageInstaller
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.Messages
import app.mjolnir.client.Platform
import app.mjolnir.client.UpdateState
import app.mjolnir.client.Updater
import app.mjolnir.client.installMjolnirDefaults
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A GitHub stand-in with release `v1.0.9` (APK + SHA256SUMS); records every request it gets. */
private class FakeReleases(val apk: ByteArray = ByteArray(40_000) { (it % 251).toByte() }) {
    val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequestData>())
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
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
                            """{"name":"mjolnir-1.0.9.apk","size":${apk.size},"browser_download_url":"${url("mjolnir-1.0.9.apk")}"},""" +
                            """{"name":"SHA256SUMS","size":100,"browser_download_url":"${url("SHA256SUMS")}"}]}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    path.endsWith("/SHA256SUMS") -> respond("$sha  mjolnir-1.0.9.apk\n")
                    path.endsWith(".apk") -> respond(apk, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, apk.size.toString()))
                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateFlowTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest fun tearDown() = scope.cancel()

    /** An installer whose permission check, settings screen and session commit are recorded instead of run. */
    private class FakeInstall(var allowed: Boolean = true, var fail: Boolean = false) {
        val settingsOpened = mutableListOf<Unit>()
        val committed = mutableListOf<File>()
        val installer = ApkInstaller(
            canRequestInstalls = { allowed },
            openInstallSettings = { settingsOpened += Unit },
            commit = { f -> if (fail) throw IOException("no session"); committed += f },
        )
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    /** A flow on a real updater that has found release 1.0.9 (the app is 1.0.5). */
    private fun flowWithRelease(install: FakeInstall, releases: FakeReleases = FakeReleases()): Pair<Updater, UpdateFlow> {
        val updater = Updater(releases.client(), Platform.ANDROID_APK, "1.0.5", File(temp.root, "updates"), scope)
        updater.start()
        await { updater.state.first { it is UpdateState.Available } }
        return updater to UpdateFlow(updater, install.installer, scope)
    }

    @Test fun bannerShowsAvailableAndProgress() {
        assertEquals(UpdateBannerUi("Update available: 1.0.9", install = true), updateBanner(UpdateState.Available("1.0.9"), false, false))
        assertEquals(UpdateBannerUi("Downloading update… 42%", install = false), updateBanner(UpdateState.Downloading("1.0.9", 42), false, false))
        assertEquals(UpdateBannerUi("Installing…", install = false), updateBanner(UpdateState.Ready("1.0.9", File("x.apk")), false, false))
        assertEquals(
            UpdateBannerUi("Update failed: checksum mismatch. Try again.", install = true),
            updateBanner(UpdateState.Failed("1.0.9", Updater.CHECKSUM_MISMATCH), false, false),
        )
        assertNull(updateBanner(UpdateState.Idle, false, false))
        // A relay too old for this app takes the slot until the user has tapped Install.
        assertEquals(UpdateBannerUi("This version needs a newer relay.", install = false), updateBanner(UpdateState.Idle, true, false))
        assertEquals(UpdateBannerUi("This version needs a newer relay.", install = false), updateBanner(UpdateState.Available("1.0.9"), true, false))
        assertEquals(UpdateBannerUi("Downloading update… 7%", install = false), updateBanner(UpdateState.Downloading("1.0.9", 7), true, false))

        // End to end: Install downloads with progress, then hands the verified file to the installer.
        val install = FakeInstall()
        val releases = FakeReleases()
        val (updater, flow) = flowWithRelease(install, releases)
        assertEquals(UpdateBannerUi("Update available: 1.0.9", install = true), await { flow.banner.first { it != null } })
        await { flow.install().join() }
        val ready = updater.state.value as UpdateState.Ready
        assertEquals(listOf(ready.file), install.committed)
        assertEquals(File(File(temp.root, "updates"), "mjolnir-1.0.9.apk"), ready.file)
        assertTrue(releases.apk.contentEquals(ready.file.readBytes()))
        assertEquals(UpdateBannerUi("Installing…", install = false), await { flow.banner.first { it?.text == Messages.INSTALLING } })
        // GitHub never sees the relay's token or headers: the updater has its own client.
        for (r in releases.requests) {
            assertNull(r.headers[HttpHeaders.Authorization])
            assertNull(r.headers["Mjolnir-App-Version"])
        }
    }

    @Test fun signatureMismatchShowsReinstallHint() {
        val hint = "Update failed: the update is signed differently. Reinstall once from the release page."
        assertEquals(hint, Messages.UPDATE_SIGNED_DIFFERENTLY)
        assertEquals(hint, installStatusMessage(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertEquals(hint, installStatusMessage(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null))
        // Other failures keep the shared "Try again" copy.
        assertEquals("Update failed: the install was cancelled. Try again.", installStatusMessage(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals("Update failed: the installer didn't finish. Try again.", installStatusMessage(PackageInstaller.STATUS_FAILURE, "boom"))

        // The receiver's status reaches the banner (no Install: the same file would be refused again), not a hang.
        val install = FakeInstall()
        val (updater, flow) = flowWithRelease(install)
        await { flow.install().join() }
        flow.onInstallStatus(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match")
        assertTrue(updater.state.value is UpdateState.Failed)
        assertEquals(UpdateBannerUi(hint, install = false), await { flow.banner.first { it?.text == hint } })
    }

    @Test fun pendingUserActionIsNotAnError() {
        assertNull(installStatusMessage(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
        assertNull(installStatusMessage(PackageInstaller.STATUS_SUCCESS, null))

        val install = FakeInstall()
        val (updater, flow) = flowWithRelease(install)
        await { flow.install().join() }
        flow.onInstallStatus(PackageInstaller.STATUS_PENDING_USER_ACTION, null)
        assertTrue(updater.state.value is UpdateState.Ready)
        assertEquals(UpdateBannerUi("Installing…", install = false), await { flow.banner.first { it?.text == Messages.INSTALLING } })

        // A session that can't even be written fails the install; Install then fetches it again.
        val broken = FakeInstall(fail = true)
        val (updater2, flow2) = flowWithRelease(broken)
        await { flow2.install().join() }
        assertEquals(UpdateState.Failed("1.0.9", INSTALLER_DIDNT_START), updater2.state.value)
        broken.fail = false
        await { flow2.install().join() }
        assertEquals(1, broken.committed.size)
    }

    @Test fun unknownSourcesNotAllowedShowsHint() {
        val install = FakeInstall(allowed = false)
        val (updater, flow) = flowWithRelease(install)
        await { flow.install().join() }
        assertTrue(updater.state.value is UpdateState.Ready)
        assertEquals(1, install.settingsOpened.size)
        assertTrue(install.committed.isEmpty())
        val hint = UpdateBannerUi("Allow Mjolnir to install updates, then tap Install again.", install = true)
        assertEquals(hint, await { flow.banner.first { it == hint } })

        // Allowed now: Install hands over the file already downloaded.
        install.allowed = true
        await { flow.install().join() }
        assertEquals(1, install.committed.size)
        assertEquals(1, install.settingsOpened.size)
        assertEquals(UpdateBannerUi("Installing…", install = false), await { flow.banner.first { it?.text == Messages.INSTALLING } })
    }

    @Test fun doubleTapInstallsOnce() {
        val install = FakeInstall()
        val (_, flow) = flowWithRelease(install)
        val first = flow.install()
        val second = flow.install()
        await { first.join(); second.join() }
        assertEquals(1, install.committed.size)
    }

    @Test fun updateAvailableEventReachesTheUpdaterInTheForeground() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = FakeRelayApi()
        val foreground = MutableStateFlow(false)
        val pushed = mutableListOf<String>()
        UpdateWatcher({ api.events() }, CoroutineScope(dispatcher), foreground, onUpdateAvailable = { pushed += it }, retryDelayMs = 10)
        advanceUntilIdle()
        assertEquals(0, api.events.subscriptionCount.value)

        foreground.value = true; advanceUntilIdle()
        assertEquals(1, api.events.subscriptionCount.value)
        api.events.emit(RelayEvent.Connected)
        api.events.emit(RelayEvent.UpdateAvailable("1.0.9")); advanceUntilIdle()
        assertEquals(listOf("1.0.9"), pushed)

        foreground.value = false; advanceUntilIdle()
        assertEquals(0, api.events.subscriptionCount.value)
    }
}
