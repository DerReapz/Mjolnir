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
    /** True as soon as the events stream says it is connected, false when it drops or this service stops. */
    private val onConnectionChange: (Boolean) -> Unit = {},
    /** Runs when the relay says [deviceId] is no longer paired (before the peer list is refreshed). */
    private val onUnpaired: (deviceId: String) -> Unit = {},
    /** Runs when a space this device is in changed (members, name, admin) or was deleted; the peer list is refreshed after it. */
    private val onSpaceChanged: (spaceId: String) -> Unit = {},
    private val onSpaceDeleted: (RelayEvent.SpaceDeleted) -> Unit = {},
) {
    /** Collects relay events until cancelled; RelayException(UNAUTHORIZED) and any drain failure propagate. */
    suspend fun run() {
        try {
            collect()
        } finally {
            onConnectionChange(false)
        }
    }

    private suspend fun collect() {
        api.events().collect { event ->
            when (event) {
                is RelayEvent.Connected -> { onConnectionChange(true); drain(); onConnected() }
                is RelayEvent.Disconnected -> onConnectionChange(false)
                is RelayEvent.DeliveryArrived -> drain()
                is RelayEvent.PairRequested -> onPairRequest(event)
                is RelayEvent.Paired -> onPeersChanged()
                is RelayEvent.Unpaired -> { onUnpaired(event.deviceId); onPeersChanged() }
                is RelayEvent.SpaceChanged -> { onSpaceChanged(event.spaceId); onPeersChanged() }
                is RelayEvent.SpaceDeleted -> { onSpaceDeleted(event); onPeersChanged() }
                // The guest room UI arrives later; for now a guest joining or leaving just refreshes the peer list.
                is RelayEvent.GuestJoined, is RelayEvent.RoomEnded -> onPeersChanged()
                is RelayEvent.Downloaded -> Unit
            }
        }
    }

    private suspend fun drain() {
        drainer.drain()
        afterDrain()
    }
}
