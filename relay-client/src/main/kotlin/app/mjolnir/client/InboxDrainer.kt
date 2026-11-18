package app.mjolnir.client

import app.mjolnir.protocol.Delivery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InboxDrainer(
    private val api: RelayApi,
    private val seen: SeenDeliveries,
    private val handle: suspend (Delivery) -> Unit,
) {
    private val mutex = Mutex()

    /** Handles each unseen delivery, then acks it. Returns the number handled. A failing handler leaves its delivery unacked. */
    suspend fun drain(): Int = mutex.withLock {
        var handled = 0
        for (d in api.inbox()) {
            if (!seen.contains(d.id)) {
                try {
                    handle(d)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                seen.add(d.id)
                handled++
            }
            api.ack(d.id)
        }
        handled
    }
}
