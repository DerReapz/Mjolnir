@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.mjolnir.desktop

import app.mjolnir.client.*
import app.mjolnir.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class AppControllerTest {
    private val phone = Peer("ph", "Pixel 8", DeviceKind.PHONE)
    @TempDir lateinit var dir: File

    private fun TestScope.controller(api: RelayApi, urls: MutableList<String> = mutableListOf()): Pair<AppController, CoroutineScope> {
        File(dir, "config.json").writeText(
            """{"relayUrl":"https://a.example","deviceId":"old-id","token":"old-token","deviceName":"PC","saveDir":"${File(dir, "save").path.replace("\\", "\\\\")}"}"""
        )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return AppController(dir, scope) { url, _ -> urls += url; api } to scope
    }

    private fun TestScope.notes(c: AppController): MutableList<String> {
        val notes = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.notifications.collect { notes += it } }
        return notes
    }

    // the accept call outlives the dialog's scope; the request clears only after the relay answered
    @Test fun acceptCompletesAfterWindowScopeIsCancelled() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val gate = CompletableDeferred<Unit>()
        val api = object : RelayApi by fake {
            override suspend fun acceptPairing(pairingId: String) { gate.await(); fake.acceptPairing(pairingId) }
        }
        val (c, scope) = controller(api)
        val notes = notes(c)
        val req = RelayEvent.PairRequested("pair-1", phone)
        c.onPairRequested(req)

        val window = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        window.launch { c.answerPairRequest(req, accept = true) }; runCurrent()
        assertEquals(req, c.pairRequest.value) // still showing while the call is in flight
        assertNull(c.answerPairRequest(req, accept = true)) // a second click is ignored
        window.cancel() // the dialog leaves the composition
        gate.complete(Unit); runCurrent()

        assertEquals(listOf("pair-1"), fake.accepted)
        assertNull(c.pairRequest.value)
        assertEquals(listOf(phone), c.peers.value)
        assertEquals(listOf("Paired with Pixel 8"), notes)
        scope.cancel()
    }

    // a failed answer still clears the request and says what went wrong in a sentence
    @Test fun failedRejectClearsRequestWithSentence() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override suspend fun rejectPairing(pairingId: String) = throw RelayException(RelayErrorKind.NETWORK, "offline")
        }
        val (c, scope) = controller(api)
        val notes = notes(c)
        val req = RelayEvent.PairRequested("pair-1", phone)
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = false); runCurrent()
        assertNull(c.pairRequest.value)
        assertEquals(listOf("Couldn't answer the request: no connection."), notes)
        scope.cancel()
    }

    // accepting with no target yet makes the new device the target; an existing target is kept
    @Test fun acceptRemembersTheNewDeviceOnlyWhenNoTarget() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, scope) = controller(fake)
        assertNull(c.config.value.lastPhoneId)
        val req = RelayEvent.PairRequested("pair-1", phone)
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = true); runCurrent()
        assertEquals("ph", c.config.value.lastPhoneId)

        val other = Peer("ph2", "Pixel 6", DeviceKind.PHONE)
        val req2 = RelayEvent.PairRequested("pair-2", other)
        c.onPairRequested(req2)
        c.answerPairRequest(req2, accept = true); runCurrent()
        assertEquals("ph", c.config.value.lastPhoneId)
        scope.cancel()
    }

    // the claim used the code up, so a reject asks the pair window for a fresh one; an accept does not
    @Test fun rejectAsksForAFreshCode() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        var fresh = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.codeUsedUp.collect { fresh++ } }
        val req = RelayEvent.PairRequested("pair-1", phone)
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = false); runCurrent()
        assertEquals(listOf("pair-1"), fake.rejected)
        assertEquals(1, fresh)
        val req2 = RelayEvent.PairRequested("pair-2", phone)
        c.onPairRequested(req2)
        c.answerPairRequest(req2, accept = true); runCurrent()
        assertEquals(1, fresh)
        scope.cancel()
    }

    // a failed accept can't be retried (the dialog is gone) and the claim used the code up: ask for a fresh one
    @Test fun failedAcceptAsksForAFreshCode() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override suspend fun acceptPairing(pairingId: String) = throw RelayException(RelayErrorKind.NETWORK, "offline")
        }
        val (c, scope) = controller(api)
        val notes = notes(c)
        var fresh = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.codeUsedUp.collect { fresh++ } }
        val req = RelayEvent.PairRequested("pair-1", phone)
        c.onPairRequested(req)
        c.answerPairRequest(req, accept = true); runCurrent()
        assertEquals(1, fresh)
        assertEquals(listOf("Couldn't answer the request: no connection."), notes)
        assertNull(c.config.value.lastPhoneId)
        scope.cancel()
    }

    // changing the relay runs in the controller's scope, clears credentials and registers on the new relay
    @Test fun changeRelayReRegistersOnNewRelay() = runTest {
        val fake = FakeRelayApi(); val urls = mutableListOf<String>()
        val (c, scope) = controller(fake, urls)
        val window = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        window.launch { c.changeRelay("https://b.example") }
        runCurrent(); window.cancel(); runCurrent()
        assertEquals("https://b.example", c.config.value.relayUrl)
        assertEquals("tok-1", c.config.value.token); assertEquals("dev-1", c.config.value.deviceId)
        assertEquals(listOf("PC" to DeviceKind.PC), fake.registerCalls)
        assertEquals("https://b.example", urls.last())
        scope.cancel()
    }

    // a hand-edited config with plain http to a remote host never gets a client
    @Test fun plainHttpToRemoteRelayGetsNoClient() = runTest {
        File(dir, "config.json").writeText(
            """{"relayUrl":"http://relay.example.com","deviceName":"PC","saveDir":"${File(dir, "save").path.replace("\\", "\\\\")}"}"""
        )
        val urls = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val c = AppController(dir, scope) { url, _ -> urls += url; FakeRelayApi() }
        assertNull(c.api())
        c.start(); runCurrent()
        assertTrue(urls.isEmpty())
        scope.cancel()
    }

    // a send whose window has closed reports through a tray notification
    @Test fun sendResultBecomesNotificationWhenWindowIsGone() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        val notes = notes(c)
        c.startSend(phone, listOf(Tab("https://a.com")), emptyList()) { false }; runCurrent()
        assertEquals(1, fake.sent.size)
        assertEquals(listOf("Sent 1 tab to Pixel 8"), notes)
        assertEquals("ph", c.config.value.lastPhoneId)
        scope.cancel()
    }

    // a file that cannot be read says so instead of blaming the relay
    @Test fun unreadableFileNotificationNamesTheFile() = runTest {
        val fake = FakeRelayApi()
        val (c, scope) = controller(fake)
        val notes = notes(c)
        val gone = File(dir, "gone.txt")
        c.send(phone, emptyList(), listOf(LocalUpload(gone.absolutePath, "gone.txt", "text/plain", 4)))
        val deadline = System.currentTimeMillis() + 5_000 // the read itself runs on Dispatchers.IO
        while (notes.none { it.startsWith("Not sent") } && System.currentTimeMillis() < deadline) { runCurrent(); Thread.sleep(10) }
        assertTrue("Not sent: Couldn't read gone.txt." in notes, notes.toString())
        assertTrue(fake.sent.isEmpty())
        scope.cancel()
    }

    @Test fun pairingSentences() {
        assertEquals("Couldn't get a code: no connection.", couldntGetCode("no connection"))
        assertEquals("Couldn't answer the request: the relay had a problem.", couldntAnswer("the relay had a problem"))
    }

    @Test fun queueSurvivesWindowClose() = runTest {
        val (c, _) = controller(FakeRelayApi())
        c.updateSend { it.withText("https://a.com") }
        c.openWindow(); c.openWindow()
        assertEquals(listOf("https://a.com"), c.send.value.links)
    }

    @Test fun sendSelectedKeepsUnchecked() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, _) = controller(fake)
        c.refreshPeers()
        c.updateSend { it.withTargets(listOf(phone), null).withText("https://a.com https://b.com").toggle("https://b.com") }
        c.sendSelected().join()
        assertEquals(listOf("https://b.com"), c.send.value.links)
        assertTrue(c.send.value.selected.isEmpty())
    }

    @Test fun handOffOpensSendTabOnce() = runTest {
        val (c, _) = controller(FakeRelayApi())
        val opened = mutableListOf<MainTab>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.openRequests.collect { opened += it } }
        val f1 = File(dir, "one.txt").apply { writeText("1") }
        val f2 = File(dir, "two.txt").apply { writeText("2") }
        c.handOff(listOf(f1)).join()
        c.handOff(listOf(f2)).join()
        assertEquals(listOf(MainTab.SEND, MainTab.SEND), opened)
        assertEquals(2, c.send.value.files.size)
    }

    @Test fun uncheckedFileStaysAfterSend() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, _) = controller(fake)
        c.refreshPeers()
        val a = File(dir, "a.txt").apply { writeText("a") }
        val b = File(dir, "b.txt").apply { writeText("b") }
        c.updateSend { it.withTargets(listOf(phone), null).withFiles(listOf(a, b)) }
        val keyB = uploadUnitKey(c.send.value.files.single { it.relativePath == "b.txt" })
        c.updateSend { it.toggle(keyB) }
        c.sendSelected().join()
        assertEquals(listOf("b.txt"), c.send.value.files.map { it.relativePath })
    }

    @Test fun sendNoticeGoesToTrayOnlyWhileWindowHidden() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, _) = controller(fake)
        c.refreshPeers()
        val notes = notes(c)
        c.updateSend { it.withTargets(listOf(phone), null).withText("https://a.com") }
        c.mainWindowVisible = true
        c.sendSelected().join()
        assertTrue(notes.isEmpty()); assertNotNull(c.send.value.notice)
        c.mainWindowVisible = false
        c.updateSend { it.withText("https://b.com") }
        c.sendSelected().join()
        assertEquals(1, notes.size)
    }

    @Test fun sendingClearsSelectedRowOfSentItem() = runTest {
        val fake = FakeRelayApi().apply { peers += phone }
        val (c, _) = controller(fake)
        c.refreshPeers()
        c.updateSend { it.withTargets(listOf(phone), null).withText("https://a.com").selectRow("https://a.com") }
        c.sendSelected().join()
        assertNull(c.send.value.selectedRow)
    }
}
