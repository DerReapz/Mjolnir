package app.mjolnir.client

import app.mjolnir.protocol.CodeKind
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

private const val ALREADY_MEMBER_ERROR = "already_member"

/** The limit messages say 50 whatever MJOLNIR_MAX_* the relay runs with. */
private val LIMIT_ERRORS = mapOf(
    "member_limit" to "This space is full (50 devices).",
    "space_limit" to "You're in the most spaces allowed (50).",
)

/** Copy for the relay's 400/409 claim errors, keyed by the error code in the response body. */
private val CLAIM_ERRORS = mapOf(
    OWN_CODE_ERROR to "That's this device's own code. Enter it on the other device.",
    "wrong_kind_space" to "That's a space invite. Use Join a space.",
    "wrong_kind_pairing" to "That's a pairing code. Use Pair a device.",
    "member_limit" to LIMIT_ERRORS.getValue("member_limit"),
    "space_limit" to LIMIT_ERRORS.getValue("space_limit"),
)

/** Copy for the relay's space-limit errors (create, claim or accept) and an outdated relay, or null for any other failure. */
fun spaceErrorCopy(e: RelayException): String? =
    if (e.kind == RelayErrorKind.RELAY_OUTDATED) Messages.RELAY_OUTDATED else LIMIT_ERRORS[e.message]

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
    expect: CodeKind = CodeKind.PAIRING,
): ClaimOutcome {
    val code = PairingCode.normalize(input) ?: return ClaimOutcome.Failed("That code should be 6 letters and numbers")
    val claim = try {
        api.claimPairingCode(code, expect)
    } catch (e: RelayException) {
        return when (e.kind) {
            RelayErrorKind.UNAUTHORIZED -> ClaimOutcome.Failed(Messages.RESET, RelayErrorKind.UNAUTHORIZED)
            RelayErrorKind.NOT_FOUND -> ClaimOutcome.Failed(NOT_FOUND)
            RelayErrorKind.RELAY_OUTDATED -> ClaimOutcome.Failed(Messages.RELAY_OUTDATED)
            RelayErrorKind.RATE_LIMITED -> ClaimOutcome.Failed("Too many tries. Wait a minute.")
            // Only the relay's named claim errors get their own copy; any other bad request is treated as not found.
            RelayErrorKind.INVALID -> ClaimOutcome.Failed(CLAIM_ERRORS[e.message] ?: NOT_FOUND)
            // 409s arrive as ALREADY_SENT: already_member, member_limit, space_limit.
            RelayErrorKind.ALREADY_SENT -> ClaimOutcome.Failed(
                if (e.message == ALREADY_MEMBER_ERROR) "This device is already in ${e.detail ?: "that space"}." else CLAIM_ERRORS[e.message] ?: GENERIC,
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
                RelayErrorKind.RELAY_OUTDATED -> return ClaimOutcome.Failed(Messages.RELAY_OUTDATED)
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
