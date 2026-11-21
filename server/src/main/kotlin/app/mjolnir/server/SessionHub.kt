package app.mjolnir.server

import app.mjolnir.protocol.MjolnirJson
import app.mjolnir.protocol.RelayEvent
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

/** One open event stream of a device. */
interface EventSink {
    suspend fun sendText(text: String)

    /** Drops the connection without waiting for the peer (it is stuck or gone). */
    fun abort()

    /** Closes the connection on purpose (e.g. its guest room ended); by default the same as [abort]. */
    suspend fun close(reason: String) = abort()
}

/** An [EventSink] over a relay WebSocket session. */
class WebSocketSink(private val session: WebSocketServerSession) : EventSink {
    override suspend fun sendText(text: String) = session.send(Frame.Text(text))
    override fun abort() = session.cancel("event push timed out")
    override suspend fun close(reason: String) = session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
}

/**
 * Tracks each device's open event streams and pushes [RelayEvent]s to them. [emit] never waits: events are queued
 * per device and delivered in order on [scope], each send bounded by [sendTimeoutMs]; a stream that does not take
 * its frame in time (half-open link with a full buffer) is aborted and dropped, so it can never stall a request.
 */
class SessionHub(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sendTimeoutMs: Long = 2_000,
    private val idleMs: Long = 60_000,
) {
    private val registry = Registry<EventSink>()
    private val queues = ConcurrentHashMap<String, Channel<String>>()

    fun add(deviceId: String, sink: EventSink) = registry.add(deviceId, sink)

    fun remove(deviceId: String, sink: EventSink) = registry.remove(deviceId, sink)

    fun isConnected(deviceId: String): Boolean = registry.has(deviceId)

    /** Closes every open stream of [deviceId] and returns at once; a stream that does not close in time is aborted. */
    fun disconnect(deviceId: String, reason: String) {
        for (s in registry.all(deviceId)) {
            remove(deviceId, s)
            scope.launch {
                val closed = try {
                    withTimeoutOrNull(sendTimeoutMs) { s.close(reason); true } ?: false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (!closed) runCatching { s.abort() }
            }
        }
    }

    /** Queues [event] for every open stream of [deviceId] and returns at once. */
    fun emit(deviceId: String, event: RelayEvent) {
        if (!registry.has(deviceId)) return
        val text = MjolnirJson.encodeToString(RelayEvent.serializer(), event)
        // Queue creation, enqueue and the drainer's idle exit all run inside compute, so no event is ever orphaned.
        queues.compute(deviceId) { _, existing ->
            val queue = existing ?: Channel<String>(Channel.UNLIMITED).also { scope.launch { drain(deviceId, it) } }
            queue.trySend(text)
            queue
        }
    }

    private suspend fun drain(deviceId: String, queue: Channel<String>) {
        while (true) {
            val text = withTimeoutOrNull(idleMs) { queue.receive() }
            if (text == null) {
                var retired = false
                queues.computeIfPresent(deviceId) { _, q ->
                    if (q === queue && queue.isEmpty) { retired = true; null } else q
                }
                if (retired) return
                continue
            }
            deliver(deviceId, text)
        }
    }

    private suspend fun deliver(deviceId: String, text: String) {
        for (s in registry.all(deviceId)) {
            val sent = try {
                withTimeoutOrNull(sendTimeoutMs) { s.sendText(text); true } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!sent) {
                remove(deviceId, s)
                runCatching { s.abort() }
            }
        }
    }
}
