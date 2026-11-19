package app.mjolnir.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.client.ClaimOutcome
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Messages
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.client.claimAndAwait
import app.mjolnir.protocol.PairingCode
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface PairingUi {
    data class Entering(val error: String? = null) : PairingUi
    data class Waiting(val name: String) : PairingUi
    /** This device shows [code]; [request] is the device that entered it and waits for our answer. */
    data class Showing(
        val code: String,
        val expiresAtEpochMs: Long,
        val request: Peer? = null,
        val error: String? = null,
    ) : PairingUi
    data object Expired : PairingUi
    data class Paired(val name: String) : PairingUi
    data class Failed(val message: String) : PairingUi
}

private const val GENERIC = "Something went wrong. Try again."

/**
 * Links this phone with any other device, either by entering that device's code ([submit]) or by showing a code
 * ([showCode]) and accepting the request that comes in. Every pairing is accepted on the code-showing device.
 */
class PairingViewModel(
    private val api: RelayApi,
    private val config: JsonFileStore<PhoneConfig>,
    private val pollIntervalMs: Long = 2_000,
    /** Called when the relay rejects this device's token, so the caller can reset the registration. */
    private val onUnauthorized: suspend () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingUi>(PairingUi.Entering())
    val state: StateFlow<PairingUi> = _state.asStateFlow()
    private var job: Job? = null
    private var answerJob: Job? = null
    private var requestId: String? = null // the pairing id of Showing.request

    fun submit(input: String) {
        if (PairingCode.normalize(input) == null) {
            _state.value = PairingUi.Entering("That code should be 6 letters and numbers")
            return
        }
        stopAll()
        job = viewModelScope.launch {
            var waited = false
            val outcome = claimAndAwait(api, input, onWaiting = { waited = true; _state.value = PairingUi.Waiting(it.name) }, pollMs = pollIntervalMs)
            when (outcome) {
                is ClaimOutcome.Paired -> {
                    rememberTargetIfNone(outcome.peer.deviceId)
                    _state.value = PairingUi.Paired(outcome.peer.name)
                }
                is ClaimOutcome.Failed -> {
                    if (outcome.kind == RelayErrorKind.UNAUTHORIZED) resetAfterUnauthorized()
                    // A failed claim lets the user fix the code; a failure after waiting starts over.
                    _state.value = if (waited) PairingUi.Failed(outcome.message) else PairingUi.Entering(outcome.message)
                }
            }
        }
    }

    /** Shows a new code and, while it is showing, listens for the device that enters it. */
    fun showCode() {
        stopAll()
        job = viewModelScope.launch {
            val created = try {
                api.createPairingCode()
            } catch (e: RelayException) {
                _state.value = PairingUi.Failed(failure(e))
                if (e.kind == RelayErrorKind.UNAUTHORIZED) resetAfterUnauthorized()
                return@launch
            }
            _state.value = PairingUi.Showing(created.code, created.expiresAtEpochMs)
            val remaining = (created.expiresAtEpochMs - clock()).coerceAtLeast(0)
            // Events are collected only while the code shows; running out of time ends the collection.
            val finished = withTimeoutOrNull(remaining) {
                api.events().collect { event ->
                    if (event is RelayEvent.PairRequested) {
                        _state.value.let { s ->
                            if (s is PairingUi.Showing && s.request == null) {
                                requestId = event.pairingId
                                _state.value = s.copy(request = event.from, error = null)
                            }
                        }
                    }
                }
            }
            if (finished == null && _state.value is PairingUi.Showing) _state.value = PairingUi.Expired
        }
    }

    /** Accepts or rejects the pending request, once per request: further taps while an answer is in flight do nothing. */
    fun answer(accept: Boolean) {
        val s = _state.value as? PairingUi.Showing ?: return
        val request = s.request ?: return
        val pairingId = requestId ?: return
        if (answerJob?.isActive == true) return
        answerJob = viewModelScope.launch {
            try {
                if (accept) api.acceptPairing(pairingId) else api.rejectPairing(pairingId)
            } catch (e: RelayException) {
                if (e.kind == RelayErrorKind.UNAUTHORIZED) {
                    job?.cancel()
                    _state.value = PairingUi.Failed(Messages.RESET)
                    resetAfterUnauthorized()
                } else if (e.kind == RelayErrorKind.NOT_FOUND) {
                    _state.updateShowing(s) { it.copy(request = null, error = "That request is gone. Ask them to enter the code again.") }
                } else {
                    _state.updateShowing(s) { it.copy(error = failure(e)) } // the request stays, so the user can answer again
                }
                return@launch
            }
            if (accept) {
                job?.cancel()
                rememberTargetIfNone(request.deviceId)
                _state.value = PairingUi.Paired(request.name)
            } else {
                _state.updateShowing(s) { it.copy(request = null, error = null) }
            }
        }
    }

    /** Leaves the show-a-code view: stops listening and goes back to the start. */
    fun stopShowing() = restart()

    fun restart() {
        stopAll()
        _state.value = PairingUi.Entering()
    }

    private fun stopAll() {
        job?.cancel()
        answerJob?.cancel()
        job = null
        answerJob = null
    }

    /** Applies [change] only while the state is still a Showing view. */
    private fun MutableStateFlow<PairingUi>.updateShowing(from: PairingUi.Showing, change: (PairingUi.Showing) -> PairingUi.Showing) {
        val now = value
        if (now is PairingUi.Showing && now.code == from.code) value = change(now)
    }

    private suspend fun rememberTargetIfNone(deviceId: String) {
        try {
            withContext(io) { config.update { if (it.targetPcId == null) it.copy(targetPcId = deviceId) else it } }
        } catch (e: IOException) {
            // Pairing itself succeeded; the target is picked again from the device list.
        }
    }

    private fun failure(e: RelayException) = when (e.kind) {
        RelayErrorKind.UNAUTHORIZED -> Messages.RESET
        RelayErrorKind.RATE_LIMITED -> "Too many tries. Wait a minute."
        RelayErrorKind.NETWORK -> "Can't reach the relay. Check your connection."
        else -> GENERIC
    }

    /** Resets the registration (best effort: it may fail on the network or the config file). */
    private suspend fun resetAfterUnauthorized() {
        try {
            onUnauthorized()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            // The user sees the reset message either way; registration is retried on the next use.
        } catch (e: IOException) {
            // The config couldn't be written; same as above.
        }
    }
}
