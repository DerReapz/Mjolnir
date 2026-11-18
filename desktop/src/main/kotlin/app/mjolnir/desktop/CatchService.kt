package app.mjolnir.desktop

import app.mjolnir.client.InboxDrainer
import app.mjolnir.client.RelayApi
import app.mjolnir.protocol.RelayEvent

class CatchService(
    private val api: RelayApi,
    private val drainer: InboxDrainer,
    private val onPairRequest: suspend (RelayEvent.PairRequested) -> Unit,
    private val onPeersChanged: suspend () -> Unit,
    /** Runs after every drain (e.g. to clean the staging folder). */
    private val afterDrain: suspend () -> Unit = {},
    /** Runs after the drain that follows each (re)connect; should return quickly (launch long work elsewhere). */
    private val onConnected: suspend () -> Unit = {},
) {
    /** Collects relay events until cancelled; RelayException(UNAUTHORIZED) and any drain failure propagate. */
    suspend fun run() {
        api.events().collect { event ->
            when (event) {
                is RelayEvent.Connected -> { drain(); onConnected() }
                is RelayEvent.DeliveryArrived -> drain()
                is RelayEvent.PairRequested -> onPairRequest(event)
                is RelayEvent.Paired, is RelayEvent.Unpaired -> onPeersChanged()
            }
        }
    }

    private suspend fun drain() {
        drainer.drain()
        afterDrain()
    }
}
