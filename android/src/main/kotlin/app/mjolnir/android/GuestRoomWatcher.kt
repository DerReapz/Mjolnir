package app.mjolnir.android

import app.mjolnir.client.GuestRoomController
import app.mjolnir.client.GuestRoomUi
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.RelayEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Feeds the relay's events to the guest room for as long as a room is open, whichever screen the app is showing: it
 * starts listening when the room opens and stops when it closes. This is what lets the owner see `downloaded by guest`
 * and get the `Guest joined` notification with the Guest room screen closed. A dropped connection is retried.
 */
class GuestRoomWatcher(
    private val controller: GuestRoomController,
    private val events: () -> Flow<RelayEvent>,
    scope: CoroutineScope,
    private val onGuestJoined: () -> Unit,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    init {
        scope.launch {
            controller.state.map { it is GuestRoomUi.Open }.distinctUntilChanged().collectLatest { open ->
                if (open) listen()
            }
        }
    }

    private suspend fun listen() {
        while (true) {
            try {
                events().collect { e ->
                    if (e is RelayEvent.Connected) controller.restore() // events may have been missed while reconnecting
                    val room = controller.state.value as? GuestRoomUi.Open
                    controller.onEvent(e)
                    if (e is RelayEvent.GuestJoined && room != null && room.roomId == e.roomId && room.guest == null) onGuestJoined()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RelayException) {
                // Retried below; a restore on the next connection settles anything missed.
            }
            delay(retryDelayMs)
        }
    }

    private companion object { const val RETRY_DELAY_MS = 5_000L }
}
