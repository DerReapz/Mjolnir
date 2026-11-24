package app.mjolnir.server

import app.mjolnir.protocol.RelayEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** The pending "offline" reports, one per device whose last connection closed less than the grace period ago. */
class PresenceWatch {
    internal val pending = ConcurrentHashMap<String, Job>()
}

/** The devices that share any space with [deviceId], without itself: the only ones told about its presence. */
fun RelayDeps.coMembers(deviceId: String): Set<String> =
    store.spacesOf(deviceId).flatMapTo(mutableSetOf()) { s -> store.members(s.id).map { it.id } } - deviceId

private fun RelayDeps.emitPresence(event: RelayEvent.PresenceChanged) =
    coMembers(event.deviceId).forEach { hub.emit(it, event) }

/** [deviceId] opened its first connection: any pending offline report is dropped and its co-members hear it is online. */
fun RelayDeps.onConnected(deviceId: String) {
    presence.pending.remove(deviceId)?.cancel()
    emitPresence(RelayEvent.PresenceChanged(deviceId, true))
}

/**
 * One of [deviceId]'s connections closed. Its last-seen time is written at once; when no connection is left, its
 * co-members hear it is offline after [RelayDeps.presenceGraceMs], unless it reconnected meanwhile.
 */
fun RelayDeps.onDisconnected(deviceId: String) {
    store.setLastSeen(deviceId, clock.nowMs())
    if (hub.isConnected(deviceId)) return
    lateinit var job: Job
    job = asyncScope.launch(start = CoroutineStart.LAZY) {
        try {
            delay(presenceGraceMs)
            if (!hub.isConnected(deviceId)) emitPresence(RelayEvent.PresenceChanged(deviceId, false, store.lastSeen(deviceId)))
        } finally {
            presence.pending.remove(deviceId, job)
        }
    }
    presence.pending.put(deviceId, job)?.cancel()
    job.start()
}
