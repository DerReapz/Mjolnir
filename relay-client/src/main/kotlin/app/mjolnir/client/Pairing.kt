package app.mjolnir.client

import app.mjolnir.protocol.PairingCode
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.Peer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ClaimOutcome {
    data class Paired(val peer: Peer) : ClaimOutcome
    /** [kind] is set for errors the caller must act on (UNAUTHORIZED: reset the registration). */
    data class Failed(val message: String, val kind: RelayErrorKind? = null) : ClaimOutcome
}

private const val EXPIRED = "The code expired. Get a new code on the other device."
private const val GENERIC = "Something went wrong. Try again."
private const val NOT_FOUND = "Code not found or expired. Get a new code on the other device."

/** The relay's error for claiming a code this device created itself. */
private const val OWN_CODE_ERROR = "own_code"

/**
 * Claims a pairing code typed on this device, then waits for the code's owner to accept. Shared by Android and desktop.
 * [onWaiting] is called once with the code owner after a successful claim. Cancellation propagates.
 */
suspend fun claimAndAwait(
    api: RelayApi,
    input: String,
    onWaiting: (Peer) -> Unit,
    pollMs: Long = 2_000,
    limit: Duration = 5.minutes,
): ClaimOutcome {
    val code = PairingCode.normalize(input) ?: return ClaimOutcome.Failed("That code should be 6 letters and numbers")
    val claim = try {
        api.claimPairingCode(code)
    } catch (e: RelayException) {
        return when (e.kind) {
            RelayErrorKind.UNAUTHORIZED -> ClaimOutcome.Failed(Messages.RESET, RelayErrorKind.UNAUTHORIZED)
            RelayErrorKind.NOT_FOUND -> ClaimOutcome.Failed(NOT_FOUND)
            RelayErrorKind.RATE_LIMITED -> ClaimOutcome.Failed("Too many tries. Wait a minute.")
            // Only the relay's own_code error means this device's code; any other bad request is treated as not found.
            RelayErrorKind.INVALID -> ClaimOutcome.Failed(
                if (e.message == OWN_CODE_ERROR) "That's this device's own code. Enter it on the other device." else NOT_FOUND,
            )
            RelayErrorKind.NETWORK -> ClaimOutcome.Failed("Can't reach the relay. Check your connection.")
            else -> ClaimOutcome.Failed(GENERIC)
        }
    }
    val owner = claim.owner
    onWaiting(owner)
    return withTimeoutOrNull(limit) { poll(api, claim.pairingId, owner, pollMs) } ?: ClaimOutcome.Failed(EXPIRED)
}

/** Polls until a final state; null means the code expired. */
private suspend fun poll(api: RelayApi, pairingId: String, owner: Peer, pollMs: Long): ClaimOutcome? {
    while (true) {
        delay(pollMs)
        val status = try {
            api.pairingStatus(pairingId)
        } catch (e: RelayException) {
            when (e.kind) {
                RelayErrorKind.NETWORK, RelayErrorKind.SERVER -> continue
                RelayErrorKind.NOT_FOUND -> return null
                RelayErrorKind.UNAUTHORIZED -> return ClaimOutcome.Failed(Messages.RESET, RelayErrorKind.UNAUTHORIZED)
                else -> return ClaimOutcome.Failed(GENERIC)
            }
        }
        when (status.state) {
            PairingState.ACCEPTED -> return ClaimOutcome.Paired(status.peer ?: owner)
            PairingState.REJECTED -> return ClaimOutcome.Failed("${owner.name} said no.")
            PairingState.EXPIRED -> return null
            PairingState.PENDING, PairingState.CLAIMED -> Unit
        }
    }
}
