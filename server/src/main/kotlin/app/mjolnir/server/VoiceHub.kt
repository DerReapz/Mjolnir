package app.mjolnir.server

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
