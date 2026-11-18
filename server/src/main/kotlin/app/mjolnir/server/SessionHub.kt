package app.mjolnir.server

import app.mjolnir.protocol.MjolnirJson
import app.mjolnir.protocol.RelayEvent
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Tracks each device's open WebSocket sessions and pushes [RelayEvent]s to them. */
class SessionHub {
    private val sessions = ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketServerSession>>()

    fun add(deviceId: String, session: WebSocketServerSession) {
        sessions.computeIfAbsent(deviceId) { CopyOnWriteArraySet() }.add(session)
    }

    fun remove(deviceId: String, session: WebSocketServerSession) {
        sessions[deviceId]?.let { set ->
            set.remove(session)
            if (set.isEmpty()) sessions.remove(deviceId, set)
        }
    }

    fun isConnected(deviceId: String): Boolean = sessions[deviceId]?.isNotEmpty() == true

    suspend fun emit(deviceId: String, event: RelayEvent) {
        val text = MjolnirJson.encodeToString(RelayEvent.serializer(), event)
        for (s in sessions[deviceId].orEmpty()) {
            try {
                s.send(Frame.Text(text))
            } catch (e: Exception) {
                remove(deviceId, s)
            }
        }
    }
}
