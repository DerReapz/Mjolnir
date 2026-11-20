package app.mjolnir.client

import app.mjolnir.protocol.Peer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MAX_NICKNAME_CODE_POINTS = 40

/** Local, per-device nicknames for peers, persisted through [store]. */
class Nicknames(private val store: JsonFileStore<Map<String, String>>) {
    private val state = MutableStateFlow(store.read())

    val changes: StateFlow<Map<String, String>> = state.asStateFlow()

    fun get(deviceId: String): String? = state.value[deviceId]

    /** Trims and caps at 40 code points; a blank nickname removes the entry. */
    fun set(deviceId: String, nickname: String) {
        val trimmed = nickname.trim()
        if (trimmed.isEmpty()) return remove(deviceId)
        val capped = if (trimmed.codePointCount(0, trimmed.length) > MAX_NICKNAME_CODE_POINTS) {
            trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_NICKNAME_CODE_POINTS))
        } else trimmed
        state.value = store.update { it + (deviceId to capped) }
    }

    fun remove(deviceId: String) {
        state.value = store.update { it - deviceId }
    }

    /** Forgets every nickname (the pairings they belonged to are gone, e.g. after a relay change). */
    fun clear() {
        state.value = store.update { emptyMap() }
    }

    fun displayName(peer: Peer): String = get(peer.deviceId) ?: peer.name
}
