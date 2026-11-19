@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.mjolnir.desktop

import app.mjolnir.client.*
import app.mjolnir.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        val window = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        window.launch { c.pairWithCode("abc-234") { waiting += it } }; runCurrent()
        assertEquals(listOf(other), waiting)
        window.cancel()
        advanceTimeBy(2_001); runCurrent()
        assertEquals(listOf(other), c.peers.value)
        scope.cancel()
    }
}
