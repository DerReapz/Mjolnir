@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.mjolnir.client

import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.Peer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PairingTest {
    private val owner = Peer("owner", "Pixel 8", DeviceKind.PHONE)
    private fun fake() = FakeRelayApi().apply { onClaim = { app.mjolnir.protocol.ClaimResponse("pair-1", owner) } }
    private fun failing(kind: RelayErrorKind, message: String = "x", detail: String? = null) =
        fake().apply { onClaim = { throw RelayException(kind, message, detail = detail) } }

    @Test fun badFormat() = runTest {
        val r = claimAndAwait(fake(), "12", {})
        assertEquals(ClaimOutcome.Failed("That code should be 6 letters and numbers"), r)
    }

    @Test fun notFound() = runTest {
        assertEquals(
            ClaimOutcome.Failed("Code not found or expired. Get a new code on the other device."),
            claimAndAwait(failing(RelayErrorKind.NOT_FOUND), "ABC234", {}),
        )
    }

    @Test fun rateLimited() = runTest {
        assertEquals(ClaimOutcome.Failed("Too many tries. Wait a minute."), claimAndAwait(failing(RelayErrorKind.RATE_LIMITED), "ABC234", {}))
    }

    @Test fun ownCode() = runTest {
        assertEquals(
            ClaimOutcome.Failed("That's this device's own code. Enter it on the other device."),
            claimAndAwait(failing(RelayErrorKind.INVALID, "own_code"), "ABC234", {}),
        )
    }

    @Test fun otherInvalidIsNotOwnCode() = runTest {
        assertEquals(
            ClaimOutcome.Failed("Code not found or expired. Get a new code on the other device."),
            claimAndAwait(failing(RelayErrorKind.INVALID, "HTTP 400"), "ABC234", {}),
        )
    }

    @Test fun unauthorizedOnClaim() = runTest {
        assertEquals(ClaimOutcome.Failed(Messages.RESET, RelayErrorKind.UNAUTHORIZED), claimAndAwait(failing(RelayErrorKind.UNAUTHORIZED), "ABC234", {}))
    }

    @Test fun waitingThenAccepted() = runTest {
        val api = fake().apply {
            statuses += PairingStatus(PairingState.CLAIMED); statuses += PairingStatus(PairingState.ACCEPTED, owner)
        }
        val waiting = mutableListOf<Peer>()
        val r = async { claimAndAwait(api, "abc-234", { waiting += it }) }
        runCurrent()
        assertEquals(listOf(owner), waiting)
        assertTrue(r.isActive)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(r.isActive)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(ClaimOutcome.Paired(owner), r.await())
    }

    @Test fun rejected() = runTest {
        val api = fake().apply { statuses += PairingStatus(PairingState.REJECTED) }
        assertEquals(ClaimOutcome.Failed("Pixel 8 said no."), claimAndAwait(api, "ABC234", {}))
    }

    @Test fun expiredStatus() = runTest {
        val api = fake().apply { statuses += PairingStatus(PairingState.EXPIRED) }
        assertEquals(ClaimOutcome.Failed("The code expired. Get a new code on the other device."), claimAndAwait(api, "ABC234", {}))
    }

    @Test fun timeoutExpires() = runTest {
        val r = claimAndAwait(fake(), "ABC234", {}) // stays PENDING forever; virtual time
        assertEquals(ClaimOutcome.Failed("The code expired. Get a new code on the other device."), r)
        assertEquals(5 * 60_000L, testScheduler.currentTime)
    }

    @Test fun networkBlipKeepsPolling() = runTest {
        val api = fake()
        val calls = intArrayOf(0)
        val flaky = object : RelayApi by api {
            override suspend fun pairingStatus(pairingId: String): PairingStatus {
                if (calls[0]++ < 2) throw RelayException(RelayErrorKind.NETWORK, "x")
                return PairingStatus(PairingState.ACCEPTED, owner)
            }
        }
        assertEquals(ClaimOutcome.Paired(owner), claimAndAwait(flaky, "ABC234", {}))
        assertEquals(3, calls[0])
    }

    @Test fun unauthorizedWhilePolling() = runTest {
        val api = fake()
        val bad = object : RelayApi by api {
            override suspend fun pairingStatus(pairingId: String): PairingStatus = throw RelayException(RelayErrorKind.UNAUTHORIZED, "x")
        }
        assertEquals(ClaimOutcome.Failed(Messages.RESET, RelayErrorKind.UNAUTHORIZED), claimAndAwait(bad, "ABC234", {}))
    }

    @Test fun cancellationIsRethrown() = runTest {
        val r = async { claimAndAwait(fake(), "ABC234", {}) }
        runCurrent()
        r.cancel()
        assertFailsWith<CancellationException> { r.await() }
    }

    @Test fun networkOnClaimFailsFast() = runTest {
        assertEquals(ClaimOutcome.Failed("Can't reach the relay. Check your connection."), claimAndAwait(failing(RelayErrorKind.NETWORK), "ABC234", {}))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun notFoundWhilePollingIsExpired() = runTest {
        val api = fake()
        val gone = object : RelayApi by api {
            override suspend fun pairingStatus(pairingId: String): PairingStatus = throw RelayException(RelayErrorKind.NOT_FOUND, "x")
        }
        assertEquals(ClaimOutcome.Failed("The code expired. Get a new code on the other device."), claimAndAwait(gone, "ABC234", {}))
    }

    @Test fun wrongKindMessages() = runTest {
        assertEquals(
            ClaimOutcome.Failed("That's a space invite. Use Join a space."),
            claimAndAwait(failing(RelayErrorKind.INVALID, "wrong_kind_space"), "ABC234", {}),
        )
        assertEquals(
            ClaimOutcome.Failed("That's a pairing code. Use Pair a device."),
            claimAndAwait(failing(RelayErrorKind.INVALID, "wrong_kind_pairing"), "ABC234", {}, expect = CodeKind.SPACE_INVITE),
        )
    }

    @Test fun expectIsPassedToTheRelay() = runTest {
        val api = fake()
        claimAndAwait(api, "ABC234", {}, pollMs = 1, limit = kotlin.time.Duration.parse("1ms"), expect = CodeKind.SPACE_INVITE)
        claimAndAwait(api, "ABC234", {}, pollMs = 1, limit = kotlin.time.Duration.parse("1ms"))
        assertEquals(listOf(CodeKind.SPACE_INVITE, CodeKind.PAIRING), api.claimExpects)
    }

    @Test fun alreadyMemberMessage() = runTest {
        assertEquals(
            ClaimOutcome.Failed("This device is already in Friends."),
            claimAndAwait(failing(RelayErrorKind.ALREADY_SENT, "already_member", "Friends"), "ABC234", {}, expect = CodeKind.SPACE_INVITE),
        )
        assertEquals(
            ClaimOutcome.Failed("This device is already in that space."),
            claimAndAwait(failing(RelayErrorKind.ALREADY_SENT, "already_member"), "ABC234", {}, expect = CodeKind.SPACE_INVITE),
        )
    }

    @Test fun limitMessages() = runTest {
        assertEquals(
            ClaimOutcome.Failed("This space is full (50 devices)."),
            claimAndAwait(failing(RelayErrorKind.ALREADY_SENT, "member_limit"), "ABC234", {}, expect = CodeKind.SPACE_INVITE),
        )
        assertEquals(
            ClaimOutcome.Failed("You're in the most spaces allowed (50)."),
            claimAndAwait(failing(RelayErrorKind.ALREADY_SENT, "space_limit"), "ABC234", {}, expect = CodeKind.SPACE_INVITE),
        )
    }

    @Test fun spaceErrorCopyCoversTheLimitsOnAnyCall() {
        fun e(kind: RelayErrorKind, code: String) = RelayException(kind, code)
        assertEquals("This space is full (50 devices).", spaceErrorCopy(e(RelayErrorKind.ALREADY_SENT, "member_limit")))
        assertEquals("You're in the most spaces allowed (50).", spaceErrorCopy(e(RelayErrorKind.ALREADY_SENT, "space_limit")))
        assertEquals("You're in the most spaces allowed (50).", spaceErrorCopy(e(RelayErrorKind.INVALID, "space_limit")))
        assertEquals(null, spaceErrorCopy(e(RelayErrorKind.ALREADY_SENT, "admin_active")))
        assertEquals(null, spaceErrorCopy(e(RelayErrorKind.NETWORK, "timeout")))
    }
}
