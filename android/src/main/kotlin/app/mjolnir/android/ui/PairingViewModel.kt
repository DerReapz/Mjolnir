package app.mjolnir.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Messages
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.PairingCode
import app.mjolnir.protocol.PairingState
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

sealed interface PairingUi {
    data class Entering(val error: String? = null) : PairingUi
    data class Waiting(val pcName: String) : PairingUi
    data class Paired(val pcName: String) : PairingUi
    data class Failed(val message: String) : PairingUi
}

private const val EXPIRED = "The code expired. Get a new code on the PC."

/** Claims a pairing code typed on the phone and waits for the PC's owner to accept it. */
class PairingViewModel(
    private val api: RelayApi,
    private val config: JsonFileStore<PhoneConfig>,
    private val pollIntervalMs: Long = 2_000,
    /** Called when the relay rejects this device's token, so the caller can reset the registration. */
    private val onUnauthorized: suspend () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingUi>(PairingUi.Entering())
    val state: StateFlow<PairingUi> = _state.asStateFlow()
    private var job: Job? = null

    fun submit(input: String) {
        val code = PairingCode.normalize(input)
        if (code == null) {
            _state.value = PairingUi.Entering("That code should be 6 letters and numbers")
            return
        }
        job?.cancel()
        job = viewModelScope.launch { claimAndWait(code) }
    }

    fun restart() {
        job?.cancel()
        _state.value = PairingUi.Entering()
    }

    /** Resets the registration (best effort: it may fail on the network) and tells the user the device was reset. */
    private suspend fun resetAfterUnauthorized() {
        try {
            onUnauthorized()
        } catch (e: RelayException) {
            // The user sees the reset message either way; registration is retried on the next use.
        }
    }

    private suspend fun claimAndWait(code: String) {
        val claim = try {
            api.claimPairingCode(code)
        } catch (e: RelayException) {
            if (e.kind == RelayErrorKind.UNAUTHORIZED) resetAfterUnauthorized()
            _state.value = PairingUi.Entering(
                when (e.kind) {
                    RelayErrorKind.UNAUTHORIZED -> Messages.RESET
                    RelayErrorKind.NOT_FOUND -> "Code not found or expired. Get a new code on the PC."
                    RelayErrorKind.RATE_LIMITED -> "Too many tries. Wait a minute."
                    RelayErrorKind.NETWORK -> "Can't reach the relay. Check your connection."
                    else -> "Something went wrong. Try again."
                },
            )
            return
        }
        val pc = claim.owner
        _state.value = PairingUi.Waiting(pc.name)
        val result = withTimeoutOrNull(5.minutes) { poll(claim.pairingId, pc) }
        _state.value = result ?: PairingUi.Failed(EXPIRED)
    }

    /** Polls until a final state; null means the code expired. */
    private suspend fun poll(pairingId: String, pc: app.mjolnir.protocol.Peer): PairingUi? {
        while (true) {
            delay(pollIntervalMs)
            val status = try {
                api.pairingStatus(pairingId)
            } catch (e: RelayException) {
                when (e.kind) {
                    RelayErrorKind.NETWORK, RelayErrorKind.SERVER -> continue
                    RelayErrorKind.NOT_FOUND -> return null // the pairing is gone: it expired
                    RelayErrorKind.UNAUTHORIZED -> { resetAfterUnauthorized(); return PairingUi.Failed(Messages.RESET) }
                    else -> return PairingUi.Failed("Something went wrong. Try again.")
                }
            }
            when (status.state) {
                PairingState.ACCEPTED -> {
                    val peerId = (status.peer ?: pc).deviceId
                    try {
                        withContext(io) { config.update { if (it.targetPcId == null) it.copy(targetPcId = peerId) else it } }
                    } catch (e: IOException) {
                        // Pairing itself succeeded; the target is picked again from the device list.
                    }
                    return PairingUi.Paired(pc.name)
                }
                PairingState.REJECTED -> return PairingUi.Failed("${pc.name} said no.")
                PairingState.EXPIRED -> return null
                PairingState.PENDING, PairingState.CLAIMED -> Unit
            }
        }
    }
}
