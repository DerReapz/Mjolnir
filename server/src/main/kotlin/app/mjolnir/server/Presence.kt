package app.mjolnir.server

import app.mjolnir.protocol.RelayEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The pending "offline" reports, one per device whose last connection closed less than the grace period ago, and the
 * locks that serialize each device's presence decisions (a fired offline check and a fresh online report never interleave).
 */
class PresenceWatch {
    internal val pending = ConcurrentHashMap<String, Job>()
    private val locks = Array(64) { Any() }

    /** Test hook: runs inside the lock after an offline check found the device still gone, just before the report. */
    @Volatile internal var beforeOfflineReport: (String) -> Unit = {}

    internal fun lockFor(deviceId: String): Any = locks[Math.floorMod(deviceId.hashCode(), locks.size)]

    /** True while [deviceId]'s offline report waits out the grace: its co-members still have it as online. */
    fun offlinePending(deviceId: String): Boolean = pending.containsKey(deviceId)
}

/** The devices that share any space with [deviceId], without itself: the only ones told about its presence. */
fun RelayDeps.coMembers(deviceId: String): Set<String> =
    store.spacesOf(deviceId).flatMapTo(mutableSetOf()) { s -> store.members(s.id).map { it.id } } - deviceId

/** The voice room [deviceId] is in and the members of that room's space (the only ones who may learn it), if any. */
private fun RelayDeps.voiceRoomOf(deviceId: String): Pair<String, Set<String>>? {
    val roomId = voice.roomOf(deviceId) ?: return null
    val room = store.voiceRoom(roomId) ?: return null
    return roomId to store.members(room.spaceId).mapTo(HashSet()) { it.id }
}

/** [deviceId]'s voice room as a member of [spaceId] sees it: set only when the room belongs to that space. */
fun RelayDeps.voiceRoomIn(spaceId: String, deviceId: String): String? =
    voice.roomOf(deviceId)?.takeIf { store.voiceRoom(it)?.spaceId == spaceId }

/** Sends [deviceId]'s presence to [to] (its co-members by default); its voice room goes only to members of that room's space. */
private fun RelayDeps.emitPresence(deviceId: String, online: Boolean, lastSeenMs: Long?, to: Collection<String> = coMembers(deviceId)) {
    val inVoice = voiceRoomOf(deviceId)
    for (id in to) {
        val roomId = inVoice?.takeIf { id in it.second }?.first
        hub.emit(id, RelayEvent.PresenceChanged(deviceId, online, lastSeenMs, roomId))
    }
}

/** True while [deviceId] has an open event socket or is in a voice room (its voice link is open, or within its grace). */
fun RelayDeps.presentNow(deviceId: String): Boolean = hub.isConnected(deviceId) || voice.roomOf(deviceId) != null

/** What members see as [deviceId]'s presence: present, or gone for less than the offline grace. */
fun RelayDeps.isOnline(deviceId: String): Boolean = presentNow(deviceId) || presence.offlinePending(deviceId)

/**
 * [deviceId] joined, left or switched voice rooms; [spaceIds] are the spaces of the rooms involved and [wasInVoice]
 * whether it was in a room before. Only the co-members in those spaces hear it, with the room it is in now. When the
 * voice link is its only connection (no event socket), its online state changed too, and every co-member hears that;
 * going offline writes its last-seen time. Under the presence lock, so it never interleaves with an online or offline report.
 */
fun RelayDeps.onVoiceChanged(deviceId: String, spaceIds: Collection<String>, wasInVoice: Boolean) {
    synchronized(presence.lockFor(deviceId)) {
        val inVoice = voice.roomOf(deviceId) != null
        val eventOnline = hub.isConnected(deviceId) || presence.offlinePending(deviceId)
        val online = eventOnline || inVoice
        if (!online) store.setLastSeen(deviceId, clock.nowMs())
        val coMembers = coMembers(deviceId)
        val to = if (!eventOnline && inVoice != wasInVoice) coMembers
            else spaceIds.toSet().flatMapTo(HashSet()) { s -> store.members(s).map { it.id } }.filter { it in coMembers }
        emitPresence(deviceId, online, if (online) null else store.lastSeen(deviceId), to)
    }
}

/** [deviceId] opened its first connection: any pending offline report is dropped and its co-members hear it is online. */
fun RelayDeps.onConnected(deviceId: String) {
    // The device is already in the hub, so an offline check that runs after this lock sees it connected; one that
    // holds the lock now reports offline first, and this online report follows it.
    synchronized(presence.lockFor(deviceId)) {
        presence.pending.remove(deviceId)?.cancel()
        emitPresence(deviceId, true, null)
    }
}

/**
 * One of [deviceId]'s event connections closed. Its last-seen time is written at once; when no connection is left
 * (and it is in no voice room), its co-members hear it is offline after [RelayDeps.presenceGraceMs], unless it
 * reconnected meanwhile.
 */
fun RelayDeps.onDisconnected(deviceId: String) {
    store.setLastSeen(deviceId, clock.nowMs())
    // A device still in a voice room stays online; leaving voice reports it offline then (see onVoiceChanged).
    if (presentNow(deviceId)) return
    lateinit var job: Job
    job = asyncScope.launch(start = CoroutineStart.LAZY) {
        try {
            delay(presenceGraceMs)
            synchronized(presence.lockFor(deviceId)) {
                if (!presentNow(deviceId)) {
                    presence.pending.remove(deviceId, job) // from here on a member list says offline too
                    presence.beforeOfflineReport(deviceId)
                    emitPresence(deviceId, false, store.lastSeen(deviceId))
                }
            }
        } finally {
            presence.pending.remove(deviceId, job)
        }
    }
    presence.pending.put(deviceId, job)?.cancel()
    job.start()
}
