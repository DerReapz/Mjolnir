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
}
