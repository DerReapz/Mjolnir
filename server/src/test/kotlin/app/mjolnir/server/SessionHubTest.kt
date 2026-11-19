package app.mjolnir.server

import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Tab
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A half-open peer: its send never completes. */
class StuckSink : EventSink {
    val aborted = CompletableDeferred<Unit>()
    override suspend fun sendText(text: String) = awaitCancellation()
    override fun abort() { aborted.complete(Unit) }
}

class RecordingSink : EventSink {
    val texts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override suspend fun sendText(text: String) { texts += text }
    override fun abort() {}
}

class SessionHubTest {
    @Test fun concurrentAddAndRemoveNeverDetachTheLiveSession() = runBlocking {
        val r = Registry<Any>()
        repeat(2000) {
            val old = Any(); val live = Any()
            r.add("d", old)
            val a = launch(Dispatchers.Default) { r.remove("d", old) }
            val b = launch(Dispatchers.Default) { r.add("d", live) }
            a.join(); b.join()
            assertTrue(r.has("d")); assertTrue(live in r.all("d"))
            r.remove("d", live); assertFalse(r.has("d"))
        }
    }

    @Test fun stuckSessionIsDroppedWithoutBlockingOthersAndOrderIsKept() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val hub = SessionHub(scope, sendTimeoutMs = 200, idleMs = 50)
            val stuck = StuckSink(); val live = RecordingSink()
            hub.add("d", stuck); hub.add("d", live)
            val started = System.nanoTime()
            repeat(20) { hub.emit("d", RelayEvent.Unpaired("p$it")) }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 100, "emit must not wait for the sessions")
            withTimeout(5_000) { stuck.aborted.await() }
            withTimeout(5_000) { while (live.texts.size < 20) delay(10) }
            assertEquals((0 until 20).map { "\"p$it\"" }, live.texts.map { Regex("\"p\\d+\"").find(it)!!.value })
            assertTrue(hub.isConnected("d"))
            // After the drainer retires on idle, a later event still arrives.
            delay(300)
            hub.emit("d", RelayEvent.Unpaired("late"))
            withTimeout(5_000) { while (live.texts.size < 21) delay(10) }
        } finally {
            scope.cancel()
        }
    }

    @Test fun sendIsNotDelayedByAHalfOpenRecipient() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val stuck = StuckSink()
        deps.hub.add(pc.deviceId, stuck)
        val started = System.nanoTime()
        assertEquals(HttpStatusCode.Accepted, sendStatus(phone, pc.deviceId, listOf(Tab("https://example.com/a"))))
        assertEquals(HttpStatusCode.Accepted, sendStatus(phone, pc.deviceId, listOf(Tab("https://example.com/b"))))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 1_000, "send took $ms ms")
        withTimeout(5_000) { stuck.aborted.await() }
        assertFalse(deps.hub.isConnected(pc.deviceId))
    }
}
