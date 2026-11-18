package app.mjolnir.desktop

import app.mjolnir.client.InboxDrainer
import app.mjolnir.client.RelayApi
import app.mjolnir.protocol.RelayEvent

class CatchService(
    private val api: RelayApi,
    private val drainer: InboxDrainer,
    private val onPairRequest: suspend (RelayEvent.PairRequested) -> Unit,
    private val onPeersChanged: suspend () -> Unit,
) {
    /** Collects relay events until cancelled; RelayException(UNAUTHORIZED) and other failures propagate. */
    suspend fun run() {
        api.events().collect { event ->
            when (event) {
                is RelayEvent.Connected, is RelayEvent.DeliveryArrived -> drainer.drain()
                is RelayEvent.PairRequested -> onPairRequest(event)
                is RelayEvent.Paired, is RelayEvent.Unpaired -> onPeersChanged()
            }
        }
    }
}
