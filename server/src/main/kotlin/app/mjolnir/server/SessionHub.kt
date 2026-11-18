package app.mjolnir.server

import app.mjolnir.protocol.MjolnirJson
import app.mjolnir.protocol.RelayEvent
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Atomic per-key set registry; add and remove never lose a concurrently added member. */
internal class Registry<T : Any> {
    private val map = ConcurrentHashMap<String, CopyOnWriteArraySet<T>>()

    fun add(key: String, item: T) {
        map.compute(key) { _, s -> (s ?: CopyOnWriteArraySet()).apply { add(item) } }
    }

    fun remove(key: String, item: T) {
        map.computeIfPresent(key) { _, s -> s.remove(item); if (s.isEmpty()) null else s }
    }

    fun has(key: String): Boolean = map[key]?.isNotEmpty() == true

    fun all(key: String): List<T> = map[key]?.toList().orEmpty()
}

/** Tracks each device's open WebSocket sessions and pushes [RelayEvent]s to them. */
class SessionHub {
    private val registry = Registry<WebSocketServerSession>()

    fun add(deviceId: String, session: WebSocketServerSession) = registry.add(deviceId, session)

    fun remove(deviceId: String, session: WebSocketServerSession) = registry.remove(deviceId, session)

    fun isConnected(deviceId: String): Boolean = registry.has(deviceId)

    suspend fun emit(deviceId: String, event: RelayEvent) {
        val text = MjolnirJson.encodeToString(RelayEvent.serializer(), event)
        for (s in registry.all(deviceId)) {
            try {
                s.send(Frame.Text(text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                remove(deviceId, s)
            }
        }
    }
}
