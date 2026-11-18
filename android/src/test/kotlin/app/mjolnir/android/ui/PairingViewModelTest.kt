package app.mjolnir.android.ui

import app.mjolnir.android.MainDispatcherRule
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.Peer
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @Before fun setUp() {
        config = JsonFileStore(File(tmp.root, "config.json"), serializer<PhoneConfig>()) {
            PhoneConfig("https://relay.example", "d", "t", "Pixel 8")
        }
        vm = PairingViewModel(api, config)
        api.onClaim = { ClaimResponse("p1", pc) }
    }

    @Test fun invalidCodeShowsFormatError() = runTest {
        vm.submit("K7M")
        assertEquals(PairingUi.Entering("That code should be 6 letters and numbers"), vm.state.value)
    }

    @Test fun unknownCodeShowsNotFound() = runTest {
        api.onClaim = { throw RelayException(RelayErrorKind.NOT_FOUND, "x") }
        vm.submit("k7m-2qx"); runCurrent()
        assertEquals(PairingUi.Entering("Code not found or expired. Get a new code on the PC."), vm.state.value)
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
    }

    @Test fun expiredShowsFailure() = runTest {
        api.statuses += PairingStatus(PairingState.EXPIRED)
        vm.submit("k7m-2qx"); advanceTimeBy(2_001)
        assertEquals(PairingUi.Failed("The code expired. Get a new code on the PC."), vm.state.value)
    }

    @Test fun givesUpAfterFiveMinutes() = runTest {
        vm.submit("k7m-2qx"); advanceTimeBy(290_000)
        assertEquals(PairingUi.Waiting("DESKTOP-4F2K"), vm.state.value)
        advanceTimeBy(20_000)
        assertEquals(PairingUi.Failed("The code expired. Get a new code on the PC."), vm.state.value)
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
}
