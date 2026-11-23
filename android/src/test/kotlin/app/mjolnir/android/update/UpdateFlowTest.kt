package app.mjolnir.android.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInstaller
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

    /** An installer whose permission check, settings screen, sessions and prompts are recorded instead of run. */
    private class FakeInstall(var allowed: Boolean = true, var fail: Boolean = false, var promptFails: Boolean = false) {
        val settingsOpened = mutableListOf<Unit>()
        private val prepared = mutableListOf<File>()
        val committed = mutableListOf<File>()
        val sessionIds = mutableListOf<Int>()
        val abandoned = mutableListOf<Int>()
        val dead = mutableSetOf<Int>()
        val prompts = mutableListOf<Intent>()
        val installer = ApkInstaller(
            canRequestInstalls = { allowed },
            openInstallSettings = { settingsOpened += Unit },
            prepare = { f -> if (fail) throw IOException("no session"); prepared += f; prepared.size },
            commit = { id -> sessionIds += id; committed += prepared[id - 1] },
            abandon = { id -> abandoned += id; dead += id },
            isLive = { id -> id !in dead },
            launchConfirm = { i -> if (promptFails) throw ActivityNotFoundException(); prompts += i },
        )
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    /** A flow on a real updater that has found release 1.0.9 (the app is 1.0.5). */
    private fun flowWithRelease(
        install: FakeInstall, releases: FakeReleases = FakeReleases(), foreground: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ): Pair<Updater, UpdateFlow> {
        val updater = Updater(releases.client(), Platform.ANDROID_APK, "1.0.5", File(temp.root, "updates"), scope)
        updater.start()
        await { updater.state.first { it is UpdateState.Available } }
        return updater to UpdateFlow(updater, install.installer, scope, foreground)
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
        flow.onInstallStatus(1, PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match")
        assertTrue(updater.state.value is UpdateState.Failed)
        assertEquals(UpdateBannerUi(hint, install = false), await { flow.banner.first { it?.text == hint } })
    }

    @Test fun pendingUserActionIsNotAnError() {
        assertNull(installStatusMessage(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
        assertNull(installStatusMessage(PackageInstaller.STATUS_SUCCESS, null))

        val install = FakeInstall()
        val (updater, flow) = flowWithRelease(install)
        await { flow.install().join() }
        val prompt = Intent()
        flow.onConfirmationNeeded(1, prompt)
        flow.onInstallStatus(1, PackageInstaller.STATUS_PENDING_USER_ACTION, null)
        assertTrue(updater.state.value is UpdateState.Ready)
        assertEquals(listOf(prompt), install.prompts) // in the foreground: the system's prompt opens at once
        // Until the session ends, Install reopens that prompt (some prompts send nothing when dismissed).
        assertEquals(UpdateBannerUi("Update available: 1.0.9", install = true), await { flow.banner.first { it?.install == true } })

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

    @Test fun pendingWhileBackgroundedOffersInstallThatOpensThePrompt() {
        val install = FakeInstall()
        val foreground = MutableStateFlow(true)
        val (updater, flow) = flowWithRelease(install, foreground = foreground)
        await { flow.install().join() }
        foreground.value = false // the user left before the session asked for confirmation
        val prompt = Intent()
        flow.onConfirmationNeeded(1, prompt)
        assertTrue(install.prompts.isEmpty()) // never started from the background, where it would be dropped silently
        assertTrue(updater.state.value is UpdateState.Ready)
        val offer = UpdateBannerUi("Update available: 1.0.9", install = true)
        assertEquals(offer, await { flow.banner.first { it == offer } })

        foreground.value = true
        await { flow.install().join() }
        assertEquals(listOf(prompt), install.prompts)
        assertEquals(listOf(1), install.sessionIds) // the waiting session's prompt, not a second session
    }

    @Test fun staleSessionStatusIsIgnored() {
        val install = FakeInstall()
        val (updater, flow) = flowWithRelease(install)
        await { flow.install().join() }
        flow.onInstallStatus(1, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertEquals(UpdateState.Failed("1.0.9", "the install was cancelled"), updater.state.value)
        await { flow.install().join() } // downloads again and hands over session 2
        assertEquals(listOf(1, 2), install.sessionIds)
        assertTrue(updater.state.value is UpdateState.Ready)

        flow.onInstallStatus(1, PackageInstaller.STATUS_FAILURE_ABORTED, null) // session 1's late answer
        flow.onConfirmationNeeded(1, Intent())
        assertTrue(updater.state.value is UpdateState.Ready)
        assertTrue(install.prompts.isEmpty())
        flow.onInstallStatus(-1, PackageInstaller.STATUS_FAILURE, null) // no session id at all
        assertTrue(updater.state.value is UpdateState.Ready)

        flow.onInstallStatus(2, PackageInstaller.STATUS_FAILURE_CONFLICT, null)
        assertTrue(updater.state.value is UpdateState.Failed)
    }

    @Test fun secondTapAfterHandOffCommitsNothing() {
        val install = FakeInstall()
        val (_, flow) = flowWithRelease(install)
        await { flow.install().join() }
        assertEquals(UpdateBannerUi("Installing…", install = false), await { flow.banner.first { it?.text == Messages.INSTALLING } })
        await { flow.install().join() } // e.g. a tap that raced the banner
        assertEquals(listOf(1), install.sessionIds)
    }

    @Test fun promptThatCannotOpenAbandonsTheSession() {
        val install = FakeInstall(promptFails = true)
        val (updater, flow) = flowWithRelease(install)
        await { flow.install().join() }
        flow.onConfirmationNeeded(1, Intent())
        assertEquals(listOf(1), install.abandoned)
        assertEquals(UpdateState.Failed("1.0.9", "the installer didn't finish"), updater.state.value)

        // A pending status without a prompt to open is no better.
        val noPrompt = FakeInstall()
        val (updater2, flow2) = flowWithRelease(noPrompt)
        await { flow2.install().join() }
        flow2.onConfirmationNeeded(1, null)
        assertEquals(listOf(1), noPrompt.abandoned)
        assertTrue(updater2.state.value is UpdateState.Failed)
    }

    @Test fun sessionDroppedSilentlyIsLetGoOnReturn() {
        val install = FakeInstall()
        val (_, flow) = flowWithRelease(install)
        await { flow.install().join() }
        install.dead += 1 // the system dropped it without a status
        flow.onForeground()
        val offer = UpdateBannerUi("Update available: 1.0.9", install = true)
        assertEquals(offer, await { flow.banner.first { it == offer } })
        await { flow.install().join() }
        assertEquals(listOf(1, 2), install.sessionIds)
    }

    @Test fun relayNewerThanTheAppTriggersACheck() {
        val releases = FakeReleases()
        val updater = Updater(releases.client(), Platform.ANDROID_APK, "1.0.5", File(temp.root, "updates"), scope)
        forwardRelayVersion(updater, "1.0.5", "1.0.5")
        forwardRelayVersion(updater, "1.0.5", "1.0.3")
        assertTrue(releases.requests.isEmpty()) // a relay as old as (or older than) the app means no new release
        assertTrue(updater.relayTooOld.value)
        forwardRelayVersion(updater, "1.0.5", "1.0.9")
        assertEquals(UpdateState.Available("1.0.9"), await { updater.state.first { it is UpdateState.Available } })
    }

    @Test fun updateAvailableFromAnEventStreamReachesTheUpdater() {
        val updater = Updater(FakeReleases().client(), Platform.ANDROID_APK, "1.0.5", File(temp.root, "updates"), scope)
        val passed = await { flowOf(RelayEvent.Connected, RelayEvent.UpdateAvailable("1.0.9")).forwardingUpdates(updater).toList() }
        assertEquals(2, passed.size) // the stream itself is unchanged
        assertEquals(UpdateState.Available("1.0.9"), await { updater.state.first { it is UpdateState.Available } })
    }
}
