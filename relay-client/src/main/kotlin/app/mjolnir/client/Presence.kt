package app.mjolnir.client

import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Whether a device is online, and when it was last seen (null while unknown or never). */
data class Presence(val online: Boolean, val lastSeenMs: Long?)

/** Who is online, by device id: seeded from the spaces' members, then kept current by the relay's presence events. */
class PresenceTracker {
    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()

    /** Replaces the map with what [spaces] say; a device in several spaces is the same device. */
    fun seed(spaces: List<Space>) {
        _presence.value = buildMap { for (s in spaces) for (m in s.members) put(m.deviceId, Presence(m.online, m.lastSeenMs)) }
    }

    fun onEvent(e: RelayEvent) {
        if (e is RelayEvent.PresenceChanged) _presence.update { it + (e.deviceId to Presence(e.online, e.lastSeenMs)) }
    }

    fun clear() { _presence.value = emptyMap() }
}
