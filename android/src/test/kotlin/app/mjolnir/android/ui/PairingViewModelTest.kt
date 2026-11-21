package app.mjolnir.android.ui

import app.mjolnir.android.MainDispatcherRule
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.SpaceRef
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val api = FakeRelayApi()
    private val pc = Peer("pc1", "DESKTOP-4F2K", DeviceKind.PC)
    private lateinit var config: JsonFileStore<PhoneConfig>
    private lateinit var vm: PairingViewModel
    private var onUnauthorizedCalls = 0
    private var unauthorizedFailure: Exception? = null
    private var pairedCalls = 0

    @Before fun setUp() {
        config = JsonFileStore(File(tmp.root, "config.json"), serializer<PhoneConfig>()) {
            PhoneConfig("https://relay.example", "d", "t", "Pixel 8")
        }
        vm = PairingViewModel(api, config, onUnauthorized = { onUnauthorizedCalls++; unauthorizedFailure?.let { throw it } }, io = main.dispatcher, clock = { main.dispatcher.scheduler.currentTime }, onPaired = { pairedCalls++ })
        api.onClaim = { ClaimResponse("p1", pc) }
    }

    private val phone = Peer("ph1", "Pixel 6", DeviceKind.PHONE)

    private fun TestScope.showing(): PairingUi.Showing {
        api.pairingCodeResult = PairingCodeResponse("K7M2QX", 300_000)
        vm.showCode(); runCurrent()
        return vm.state.value as PairingUi.Showing
    }

    @Test fun showCodeDisplaysTheCode() = runTest {
        assertEquals(PairingUi.Showing("K7M2QX", 300_000, null), showing())
    }

    @Test fun aSpaceJoinRequestIsNotAPairRequest() = runTest {
        showing()
        api.events.emit(RelayEvent.PairRequested("p9", phone, SpaceRef("s1", "Friends"))); runCurrent()
        assertEquals(PairingUi.Showing("K7M2QX", 300_000, null), vm.state.value)
    }

    @Test fun showCodeAcceptOnceThenPaired() = runTest {
        showing()
        api.events.emit(RelayEvent.PairRequested("p9", phone)); runCurrent()
        assertEquals(PairingUi.Showing("K7M2QX", 300_000, phone), vm.state.value)
        vm.answer(true); vm.answer(true); runCurrent()
        assertEquals(listOf("p9"), api.accepted)
        assertEquals(PairingUi.Paired("Pixel 6"), vm.state.value)
        assertEquals("ph1", config.read().targetPcId)
        assertEquals(1, pairedCalls)
        vm.answer(true); runCurrent()
        assertEquals(listOf("p9"), api.accepted)
        assertEquals(1, pairedCalls)
    }

    @Test fun rejectGoesBackToShowing() = runTest {
        showing()
        api.events.emit(RelayEvent.PairRequested("p9", phone)); runCurrent()
        // The claimed code is used up: rejecting shows a new one.
        api.pairingCodeResult = PairingCodeResponse("N3W2CD", 300_000)
        advanceTimeBy(1_000)
        vm.answer(false); vm.answer(false); runCurrent()
        assertEquals(listOf("p9"), api.rejected)
        assertEquals(PairingUi.Showing("N3W2CD", 301_000, null), vm.state.value)
        assertNull(config.read().targetPcId)
        assertEquals(0, pairedCalls)
        assertEquals(1, api.events.subscriptionCount.value)
        api.events.emit(RelayEvent.PairRequested("p10", pc)); runCurrent() // still listening
        assertEquals(pc, (vm.state.value as PairingUi.Showing).request)
    }

    /** A view model whose accept or reject fails with [kind]; everything else goes to the fake. */
    private fun failingAnswers(kind: RelayErrorKind, accept: Boolean = true, reject: Boolean = true): PairingViewModel {
        val failing = object : app.mjolnir.client.RelayApi by api {
            override suspend fun acceptPairing(pairingId: String) { if (accept) throw RelayException(kind, "x") else api.acceptPairing(pairingId) }
            override suspend fun rejectPairing(pairingId: String) { if (reject) throw RelayException(kind, "x") else api.rejectPairing(pairingId) }
        }
        return PairingViewModel(failing, config, io = main.dispatcher, clock = { main.dispatcher.scheduler.currentTime }, onPaired = { pairedCalls++ })
    }

    private suspend fun TestScope.requestOn(v: PairingViewModel) {
        api.pairingCodeResult = PairingCodeResponse("K7M2QX", 300_000)
        v.showCode(); runCurrent()
        api.events.emit(RelayEvent.PairRequested("p9", phone)); runCurrent()
        api.pairingCodeResult = PairingCodeResponse("N3W2CD", 300_000)
    }

    @Test fun rejectNotFoundShowsAFreshCode() = runTest {
        val v = failingAnswers(RelayErrorKind.NOT_FOUND)
        requestOn(v)
        v.answer(false); runCurrent()
        assertEquals(PairingUi.Showing("N3W2CD", 300_000, null, "That request is gone."), v.state.value)
        assertEquals(1, api.events.subscriptionCount.value)
    }

    @Test fun acceptNotFoundShowsAFreshCode() = runTest {
        val v = failingAnswers(RelayErrorKind.NOT_FOUND)
        requestOn(v)
        v.answer(true); runCurrent()
        assertEquals(PairingUi.Showing("N3W2CD", 300_000, null, "That request is gone."), v.state.value)
        assertEquals(0, pairedCalls)
    }

    @Test fun failedRejectStillShowsAFreshCode() = runTest {
        val v = failingAnswers(RelayErrorKind.NETWORK)
        requestOn(v)
        v.answer(false); runCurrent()
        assertEquals(PairingUi.Showing("N3W2CD", 300_000, null), v.state.value)
    }

    @Test fun acceptAfterRejectCannotActOnTheRejectedRequest() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slowCodes = object : app.mjolnir.client.RelayApi by api {
            override suspend fun createPairingCode(): PairingCodeResponse {
                if (api.rejected.isNotEmpty()) gate.await()
                return api.createPairingCode()
            }
        }
        val v = PairingViewModel(slowCodes, config, io = main.dispatcher, clock = { main.dispatcher.scheduler.currentTime })
        requestOn(v)
        v.answer(false); runCurrent() // the new code is still on its way
        assertNull((v.state.value as PairingUi.Showing).request)
        v.answer(true); runCurrent()
        assertEquals(emptyList(), api.accepted)
        gate.complete(Unit); runCurrent()
        assertEquals("N3W2CD", (v.state.value as PairingUi.Showing).code)
    }

    @Test fun countdownStartsWhenTheCodeArrivesNotFromTheRelayClock() = runTest {
        advanceTimeBy(5_000)
        api.pairingCodeResult = PairingCodeResponse("K7M2QX", 1_000) // the relay's clock is far behind this phone's
        vm.showCode(); runCurrent()
        assertEquals(PairingUi.Showing("K7M2QX", 305_000, null), vm.state.value)
        advanceTimeBy(299_000)
        assertEquals("K7M2QX", (vm.state.value as PairingUi.Showing).code)
        advanceTimeBy(2_000)
        assertEquals(PairingUi.Expired, vm.state.value)
    }

    @Test fun stopShowingAfterPairedKeepsPaired() = runTest {
        showing()
        api.events.emit(RelayEvent.PairRequested("p9", phone)); runCurrent()
        vm.answer(true); runCurrent()
        vm.stopShowing(); runCurrent()
        assertEquals(PairingUi.Paired("Pixel 6"), vm.state.value)
        assertEquals(0, api.events.subscriptionCount.value)
    }

    @Test fun ensureShowingResumesInsteadOfRestarting() = runTest {
        val first = showing()
        api.events.emit(RelayEvent.PairRequested("p9", phone)); runCurrent()
        vm.ensureShowing(); runCurrent()
        assertEquals(first.copy(request = phone), vm.state.value)
        assertEquals(1, api.events.subscriptionCount.value)
    }

    @Test fun eventsUnauthorizedResetsInsteadOfCrashing() = runTest {
        val failing = object : app.mjolnir.client.RelayApi by api {
            override fun events() = kotlinx.coroutines.flow.flow<RelayEvent> { throw RelayException(RelayErrorKind.UNAUTHORIZED, "x") }
        }
        val vm2 = PairingViewModel(failing, config, onUnauthorized = { onUnauthorizedCalls++ }, io = main.dispatcher, clock = { main.dispatcher.scheduler.currentTime })
        vm2.showCode(); runCurrent()
        assertEquals(1, onUnauthorizedCalls)
        assertEquals(PairingUi.Failed(app.mjolnir.client.Messages.RESET), vm2.state.value)
    }

    @Test fun leavingStopsListening() = runTest {
        showing()
        assertEquals(1, api.events.subscriptionCount.value)
        vm.stopShowing(); runCurrent()
        assertEquals(0, api.events.subscriptionCount.value)
        assertEquals(PairingUi.Entering(), vm.state.value)
    }

    @Test fun expiryStopsListeningAndOffersANewCode() = runTest {
        showing()
        advanceTimeBy(300_001)
        assertEquals(PairingUi.Expired, vm.state.value)
        assertEquals(0, api.events.subscriptionCount.value)
        api.pairingCodeResult = PairingCodeResponse("N3W2CD", 700_000)
        vm.showCode(); runCurrent()
        assertEquals(PairingUi.Showing("N3W2CD", 600_001, null), vm.state.value) // counted from now, not the relay's time
        assertEquals(1, api.events.subscriptionCount.value)
    }

    @Test fun acceptFailureKeepsTheRequestForAnotherTry() = runTest {
        showing()
        api.events.emit(RelayEvent.PairRequested("p9", phone)); runCurrent()
        api.failAllWith = RelayException(RelayErrorKind.NETWORK, "down")
        vm.answer(true); runCurrent()
        api.failAllWith = null
        assertEquals(phone, (vm.state.value as PairingUi.Showing).request)
        vm.answer(true); runCurrent()
        assertEquals(PairingUi.Paired("Pixel 6"), vm.state.value)
    }

    @Test fun showCodeUnauthorizedResets() = runTest {
        api.failAllWith = RelayException(RelayErrorKind.UNAUTHORIZED, "x")
        vm.showCode(); runCurrent()
        assertEquals(1, onUnauthorizedCalls)
        assertEquals(PairingUi.Failed(app.mjolnir.client.Messages.RESET), vm.state.value)
    }

    @Test fun invalidCodeShowsFormatError() = runTest {
        vm.submit("K7M")
        assertEquals(PairingUi.Entering("That code should be 6 letters and numbers"), vm.state.value)
    }

    @Test fun unknownCodeShowsNotFound() = runTest {
        api.onClaim = { throw RelayException(RelayErrorKind.NOT_FOUND, "x") }
        vm.submit("k7m-2qx"); runCurrent()
        assertEquals(PairingUi.Entering("Code not found or expired. Get a new code on the other device."), vm.state.value)
    }

    @Test fun rateLimitedShowsWait() = runTest {
        api.onClaim = { throw RelayException(RelayErrorKind.RATE_LIMITED, "x") }
        vm.submit("k7m-2qx"); runCurrent()
        assertEquals(PairingUi.Entering("Too many tries. Wait a minute."), vm.state.value)
    }

    @Test fun acceptedPairingSelectsPcAsTarget() = runTest {
        api.statuses += listOf(PairingStatus(PairingState.CLAIMED), PairingStatus(PairingState.ACCEPTED, pc))
        vm.submit("k7m-2qx"); runCurrent()
        assertEquals(PairingUi.Waiting("DESKTOP-4F2K"), vm.state.value)
        advanceTimeBy(4_001)
        assertEquals(PairingUi.Paired("DESKTOP-4F2K"), vm.state.value)
        assertEquals("pc1", config.read().targetPcId)
        assertEquals(1, pairedCalls)
    }

    @Test fun acceptedKeepsExistingTarget() = runTest {
        config.update { it.copy(targetPcId = "other") }
        api.statuses += PairingStatus(PairingState.ACCEPTED, pc)
        vm.submit("k7m-2qx"); advanceTimeBy(2_001)
        assertEquals(PairingUi.Paired("DESKTOP-4F2K"), vm.state.value)
        assertEquals("other", config.read().targetPcId)
    }

    @Test fun rejectedShowsFailure() = runTest {
        api.statuses += PairingStatus(PairingState.REJECTED)
        vm.submit("k7m-2qx"); advanceTimeBy(2_001)
        assertEquals(PairingUi.Failed("DESKTOP-4F2K said no."), vm.state.value)
        assertNull(config.read().targetPcId)
        assertEquals(0, pairedCalls)
    }

    @Test fun expiredShowsFailure() = runTest {
        api.statuses += PairingStatus(PairingState.EXPIRED)
        vm.submit("k7m-2qx"); advanceTimeBy(2_001)
        assertEquals(PairingUi.Failed("The code expired. Get a new code on the other device."), vm.state.value)
    }

    @Test fun givesUpAfterFiveMinutes() = runTest {
        vm.submit("k7m-2qx"); advanceTimeBy(290_000)
        assertEquals(PairingUi.Waiting("DESKTOP-4F2K"), vm.state.value)
        advanceTimeBy(20_000)
        assertEquals(PairingUi.Failed("The code expired. Get a new code on the other device."), vm.state.value)
    }

    @Test fun networkBlipsKeepPolling() = runTest {
        api.statuses += PairingStatus(PairingState.ACCEPTED, pc)
        api.failAllWith = RelayException(RelayErrorKind.NETWORK, "down")
        vm.submit("k7m-2qx"); runCurrent()
        // claim itself fails while the network is down; retry with it back for the claim, then drop it for polling
        api.failAllWith = null
        vm.submit("k7m-2qx"); runCurrent()
        api.failAllWith = RelayException(RelayErrorKind.NETWORK, "down")
        advanceTimeBy(4_001)
        assertEquals(PairingUi.Waiting("DESKTOP-4F2K"), vm.state.value)
        api.failAllWith = null
        advanceTimeBy(2_000)
        assertEquals(PairingUi.Paired("DESKTOP-4F2K"), vm.state.value)
    }

    @Test fun restartGoesBackToEntering() = runTest {
        vm.submit("k7m-2qx"); runCurrent()
        vm.restart(); runCurrent()
        assertEquals(PairingUi.Entering(), vm.state.value)
    }

    @Test fun unauthorizedClaimShowsResetEvenIfResetFails() = runTest {
        unauthorizedFailure = RelayException(RelayErrorKind.NETWORK, "down")
        api.onClaim = { throw RelayException(RelayErrorKind.UNAUTHORIZED, "x") }
        vm.submit("k7m-2qx"); runCurrent()
        assertEquals(1, onUnauthorizedCalls)
        assertEquals(PairingUi.Entering(app.mjolnir.client.Messages.RESET), vm.state.value)
    }

    @Test fun unauthorizedClaimShowsResetEvenIfTheConfigCannotBeWritten() = runTest {
        unauthorizedFailure = java.io.IOException("disk full")
        api.onClaim = { throw RelayException(RelayErrorKind.UNAUTHORIZED, "x") }
        vm.submit("k7m-2qx"); runCurrent()
        assertEquals(1, onUnauthorizedCalls)
        assertEquals(PairingUi.Entering(app.mjolnir.client.Messages.RESET), vm.state.value)
    }

    @Test fun unauthorizedWhilePollingShowsResetEvenIfResetFails() = runTest {
        unauthorizedFailure = RelayException(RelayErrorKind.RATE_LIMITED, "slow")
        vm.submit("k7m-2qx"); runCurrent()
        api.failAllWith = RelayException(RelayErrorKind.UNAUTHORIZED, "x")
        advanceTimeBy(2_001)
        assertEquals(1, onUnauthorizedCalls)
        assertEquals(PairingUi.Failed(app.mjolnir.client.Messages.RESET), vm.state.value)
    }

    @Test fun slowNetworkCallsCountTowardFiveMinuteCap() = runTest {
        api.onClaim = { ClaimResponse("p1", pc) }
        vm.submit("k7m-2qx"); runCurrent()
        api.failAllWith = null
        // each poll takes 60s of virtual time to fail
        val slow = object : app.mjolnir.client.RelayApi by api {
            override suspend fun pairingStatus(pairingId: String): PairingStatus {
                kotlinx.coroutines.delay(60_000); throw RelayException(RelayErrorKind.NETWORK, "timeout")
            }
        }
        val vm2 = PairingViewModel(slow, config, io = main.dispatcher)
        vm2.submit("k7m-2qx"); runCurrent()
        advanceTimeBy(300_001)
        assertEquals(PairingUi.Failed("The code expired. Get a new code on the other device."), vm2.state.value)
    }
}
