@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.mjolnir.desktop

import app.mjolnir.client.*
import app.mjolnir.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class PairEntryTest {
    @TempDir lateinit var dir: File
    private val other = Peer("pc2", "Laptop", DeviceKind.PC)

    // the claim keeps running and refreshes peers after the window's scope is gone
    @Test fun claimCompletesAfterWindowScopeIsCancelled() = runTest {
        File(dir, "config.json").writeText("""{"relayUrl":"https://a.example","deviceId":"d","token":"t","deviceName":"PC"}""")
        val fake = FakeRelayApi().apply {
            onClaim = { ClaimResponse("pair-1", other) }
            statuses += PairingStatus(PairingState.ACCEPTED, other)
            peers += other
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val c = AppController(dir, scope) { _, _ -> fake }
        val waiting = mutableListOf<Peer>()
        val notes = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { c.notifications.collect { notes += it } }
        val paired = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { c.pairedWith.collect { paired += it } }
        var outcome: ClaimOutcome? = null
        val window = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        window.launch { outcome = c.pairWithCode("abc-234") { waiting += it } }; runCurrent()
        assertEquals(listOf(other), waiting)
        window.cancel()
        advanceTimeBy(2_001); runCurrent()
        assertEquals(listOf(other), c.peers.value)
        assertEquals("pc2", c.config.value.lastPhoneId)
        assertNull(outcome) // the window's own await was cancelled; the claim itself finished
        assertEquals(listOf("Paired with Laptop"), notes) // window gone: tray notification
        assertEquals(listOf("Laptop"), paired)
        scope.cancel()
    }

    // no relay configured: the failure still reaches deliver (or a notification), never silence
    @Test fun noRelayClientStillReportsFailure() = runTest {
        File(dir, "config.json").writeText("""{"relayUrl":"","deviceName":"PC"}""")
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val c = AppController(dir, scope) { _, _ -> FakeRelayApi() }
        val delivered = mutableListOf<ClaimOutcome>()
        val r = async { c.pairWithCode("ABC234", deliver = { delivered += it; true }) {} }
        runCurrent()
        val expected = ClaimOutcome.Failed("Can't reach the relay. Check your connection.")
        assertEquals(expected, r.await())
        assertEquals(listOf<ClaimOutcome>(expected), delivered)

        val notes = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { c.notifications.collect { notes += it } }
        val r2 = async { c.pairWithCode("ABC234") {} }
        runCurrent(); r2.await()
        assertEquals(listOf(expected.message), notes)
        scope.cancel()
    }
}
