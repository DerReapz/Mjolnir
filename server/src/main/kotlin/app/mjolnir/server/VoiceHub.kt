package app.mjolnir.server

import io.ktor.server.websocket.WebSocketServerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One open voice socket of [deviceId] in [roomId]. Frames for it are queued here and written by one sender coroutine:
 * [control] carries roster text frames (never dropped; a link that lets them pile up is cut), [audio] at most
 * [AUDIO_QUEUE] frames, a frame that does not fit being dropped for this receiver only.
 */
class VoiceLink(val deviceId: String, val roomId: String, val session: WebSocketServerSession) {
    internal val control = Channel<String>(CONTROL_QUEUE)
    internal val audio = Channel<ByteArray>(AUDIO_QUEUE)
    internal val queued = AtomicInteger()
    internal val dropped = AtomicInteger()

    /** Queues [frame] unless the queue is full (then this receiver just misses it). */
    internal fun offerAudio(frame: ByteArray) {
        if (audio.trySend(frame).isSuccess) queued.incrementAndGet() else dropped.incrementAndGet()
    }

    companion object {
        const val AUDIO_QUEUE = 16
        const val CONTROL_QUEUE = 32
    }
}

/** Who is in which voice room. In memory only: rooms are stored, participants are not. Thread-safe. */
class VoiceHub {
    sealed interface JoinResult {
        data object JOINED : JoinResult
        data object FULL : JoinResult
        data class MOVED(val previousRoomId: String) : JoinResult
    }

    private val lock = Any()
    private val rooms = HashMap<String, LinkedHashSet<String>>()
    private val roomByDevice = HashMap<String, String>()

    /** Task 2 sets this to close the voice sockets of [deviceIds] that were removed from [roomId] by the relay. */
    @Volatile var onRemoved: (roomId: String, deviceIds: List<String>) -> Unit = { _, _ -> }

    /** Each device's open voice socket (a device has at most one: a new one replaces it). */
    internal val links = ConcurrentHashMap<String, VoiceLink>()

    /** The grace timers of devices whose voice socket dropped: each removes its device from its room when it fires. */
    internal val graces = ConcurrentHashMap<String, Job>()

    /**
     * The roster each room's sockets were last sent (written under the room's lock). Audio is indexed by it, not by
     * [participants], which may already be newer than what the receivers hold.
     */
    internal val sentRosters = ConcurrentHashMap<String, List<String>>()

    /** The space of each room someone joined, so its members can be told even after the room is deleted. */
    internal val roomSpaces = ConcurrentHashMap<String, String>()

    private val deviceLocks = Array(64) { Any() }
    private val roomLocks = Array(64) { Any() }

    /** Serializes one device's socket decisions (join, drop, grace expiry, kick), as [PresenceWatch] does for presence. */
    internal fun lockFor(deviceId: String): Any = deviceLocks[Math.floorMod(deviceId.hashCode(), deviceLocks.size)]

    /** Serializes what a room's sockets are sent, so a roster and the audio indexed by it are queued in order. */
    internal fun roomLockFor(roomId: String): Any = roomLocks[Math.floorMod(roomId.hashCode(), roomLocks.size)]

    /** Test hook: runs on a voice socket right after its join, before the room and membership are checked again. */
    @Volatile internal var afterJoin: (roomId: String, deviceId: String) -> Unit = { _, _ -> }

    fun linkOf(deviceId: String): VoiceLink? = links[deviceId]

    /** Audio frames accepted for [deviceId]'s socket and not yet written: at most [VoiceLink.AUDIO_QUEUE] queued plus the one being written. */
    internal fun queued(deviceId: String): Int = links[deviceId]?.queued?.get() ?: 0

    /** Audio frames [deviceId]'s socket missed because its queue was full. */
    internal fun dropped(deviceId: String): Int = links[deviceId]?.dropped?.get() ?: 0

    fun participants(roomId: String): List<String> = synchronized(lock) { rooms[roomId]?.toList() ?: emptyList() }

    fun roomOf(deviceId: String): String? = synchronized(lock) { roomByDevice[deviceId] }

    /**
     * Puts [deviceId] in [roomId], leaving the room it was in. [JoinResult.FULL] (and no change) when the room already
     * holds [max] others; joining the room one is already in is [JoinResult.JOINED].
     */
    fun join(roomId: String, deviceId: String, max: Int): JoinResult = synchronized(lock) {
        val current = roomByDevice[deviceId]
        if (current == roomId) return JoinResult.JOINED
        val members = rooms.getOrPut(roomId) { LinkedHashSet() }
        if (members.size >= max) {
            if (members.isEmpty()) rooms.remove(roomId)
            return JoinResult.FULL
        }
        members.add(deviceId)
        roomByDevice[deviceId] = roomId
        if (current != null) {
            removeLocked(current, deviceId)
            JoinResult.MOVED(current)
        } else JoinResult.JOINED
    }

    /** Removes [deviceId] from [roomId]; false when it was not there. */
    fun leave(roomId: String, deviceId: String): Boolean = synchronized(lock) { removeLocked(roomId, deviceId) }

    /** Empties [roomId] (it was deleted) and tells [onRemoved]; returns who was in it. */
    fun closeRoom(roomId: String): List<String> {
        val gone = synchronized(lock) {
            val members = rooms.remove(roomId)?.toList() ?: emptyList()
            members.forEach { roomByDevice.remove(it) }
            members
        }
        if (gone.isNotEmpty()) onRemoved(roomId, gone)
        return gone
    }

    private fun removeLocked(roomId: String, deviceId: String): Boolean {
        val members = rooms[roomId] ?: return false
        if (!members.remove(deviceId)) return false
        if (members.isEmpty()) rooms.remove(roomId)
        if (roomByDevice[deviceId] == roomId) roomByDevice.remove(deviceId)
        return true
    }
}
