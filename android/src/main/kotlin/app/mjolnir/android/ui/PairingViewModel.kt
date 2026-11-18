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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairingUi {
    data class Entering(val error: String? = null) : PairingUi
    data class Waiting(val pcName: String) : PairingUi
    data class Paired(val pcName: String) : PairingUi
    data class Failed(val message: String) : PairingUi
}

private const val POLL_LIMIT_MS = 5 * 60_000L
private const val EXPIRED = "The code expired. Get a new code on the PC."

/** Claims a pairing code typed on the phone and waits for the PC's owner to accept it. */
class PairingViewModel(
    private val api: RelayApi,
    private val config: JsonFileStore<PhoneConfig>,
    private val pollIntervalMs: Long = 2_000,
    /** Called when the relay rejects this device's token, so the caller can reset the registration. */
    private val onUnauthorized: suspend () -> Unit = {},
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

    private suspend fun claimAndWait(code: String) {
        val claim = try {
            api.claimPairingCode(code)
        } catch (e: RelayException) {
            if (e.kind == RelayErrorKind.UNAUTHORIZED) {
                onUnauthorized()
                _state.value = PairingUi.Entering(Messages.RESET)
                return
            }
            _state.value = PairingUi.Entering(
                when (e.kind) {
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
        var waited = 0L
        while (waited < POLL_LIMIT_MS) {
            delay(pollIntervalMs)
            waited += pollIntervalMs
            val status = try {
                api.pairingStatus(claim.pairingId)
            } catch (e: RelayException) {
                if (e.kind == RelayErrorKind.NETWORK || e.kind == RelayErrorKind.SERVER) continue
                if (e.kind == RelayErrorKind.NOT_FOUND) break // the pairing is gone: it expired
                if (e.kind == RelayErrorKind.UNAUTHORIZED) onUnauthorized()
                _state.value = PairingUi.Failed("Something went wrong. Try again.")
                return
            }
            when (status.state) {
                PairingState.ACCEPTED -> {
                    val peerId = (status.peer ?: pc).deviceId
                    try {
                        config.update { if (it.targetPcId == null) it.copy(targetPcId = peerId) else it }
                    } catch (e: IOException) {
                        // Pairing itself succeeded; the target is picked again from the device list.
                    }
                    _state.value = PairingUi.Paired(pc.name)
                    return
                }
                PairingState.REJECTED -> { _state.value = PairingUi.Failed("${pc.name} said no."); return }
                PairingState.EXPIRED -> break
                PairingState.PENDING, PairingState.CLAIMED -> Unit
            }
        }
        _state.value = PairingUi.Failed(EXPIRED)
    }
}
