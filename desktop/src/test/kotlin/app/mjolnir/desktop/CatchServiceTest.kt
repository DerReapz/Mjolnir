@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.mjolnir.desktop

import app.mjolnir.client.*
import app.mjolnir.protocol.*
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CatchServiceTest {
    private val phone = Peer("ph", "Pixel 8", DeviceKind.PHONE)

    // a Connected event drains the inbox
    @Test fun drainsOnConnect() = runTest {
        val api = FakeRelayApi().apply { inbox += delivery("d1") }; val handled = mutableListOf<String>()
        val svc = CatchService(api, InboxDrainer(api, MemorySeen()) { handled += it.id }, {}, {})
        val job = launch { svc.run() }; runCurrent()
        api.events.emit(RelayEvent.Connected); runCurrent()
        assertEquals(listOf("d1"), handled); job.cancel()
    }
    // a DeliveryArrived event drains the inbox
    @Test fun drainsOnDeliveryEvent() = runTest {
        val api = FakeRelayApi(); val handled = mutableListOf<String>()
        val svc = CatchService(api, InboxDrainer(api, MemorySeen()) { handled += it.id }, {}, {})
        val job = launch { svc.run() }; runCurrent()
        val d = delivery("d2"); api.inbox += d
        api.events.emit(RelayEvent.DeliveryArrived(d)); runCurrent()
        assertEquals(listOf("d2"), handled); job.cancel()
    }
    // pair requests reach the callback
    @Test fun forwardsPairRequests() = runTest {
        val api = FakeRelayApi(); val got = mutableListOf<RelayEvent.PairRequested>()
        val svc = CatchService(api, InboxDrainer(api, MemorySeen()) {}, { got += it }, {})
        val job = launch { svc.run() }; runCurrent()
        val ev = RelayEvent.PairRequested("p1", phone)
        api.events.emit(ev); runCurrent()
        assertEquals(listOf(ev), got); job.cancel()
    }
    // Paired and Unpaired both refresh the peer list
    @Test fun refreshesPeersOnPairedAndUnpaired() = runTest {
        val api = FakeRelayApi(); var changed = 0
        val svc = CatchService(api, InboxDrainer(api, MemorySeen()) {}, {}, { changed++ })
        val job = launch { svc.run() }; runCurrent()
        api.events.emit(RelayEvent.Paired(phone)); api.events.emit(RelayEvent.Unpaired("ph")); runCurrent()
        assertEquals(2, changed); job.cancel()
    }
    // R15: a rejected token ends run() with UNAUTHORIZED so Main can re-register
    @Test fun unauthorizedFromEventsPropagates() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override fun events(): Flow<RelayEvent> = flow { throw RelayException(RelayErrorKind.UNAUTHORIZED, "unauthorized") }
        }
        val svc = CatchService(api, InboxDrainer(api, MemorySeen()) {}, {}, {})
        val e = assertFailsWith<RelayException> { svc.run() }
        assertEquals(RelayErrorKind.UNAUTHORIZED, e.kind)
    }
    // R15: any drain failure ends run() too (Main restarts it after a backoff)
    @Test fun drainFailurePropagates() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override suspend fun inbox(): List<Delivery> = throw RelayException(RelayErrorKind.NETWORK, "offline")
        }
        val svc = CatchService(api, InboxDrainer(api, MemorySeen()) {}, {}, {})
        val result = async { runCatching { svc.run() } }; runCurrent()
        fake.events.emit(RelayEvent.Connected); runCurrent()
        assertEquals(RelayErrorKind.NETWORK, (result.await().exceptionOrNull() as RelayException).kind)
    }
    // each drain is followed by afterDrain; Connected also calls onConnected after draining
    @Test fun afterDrainAndOnConnectedHooks() = runTest {
        val api = FakeRelayApi().apply { inbox += delivery("d1") }; val log = mutableListOf<String>()
        val svc = CatchService(api, InboxDrainer(api, MemorySeen()) { log += "handle ${it.id}" }, {}, {},
            afterDrain = { log += "afterDrain" }, onConnected = { log += "connected" })
        val job = launch { svc.run() }; runCurrent()
        api.events.emit(RelayEvent.Connected); runCurrent()
        api.events.emit(RelayEvent.DeliveryArrived(delivery("d1"))); runCurrent()
        assertEquals(listOf("handle d1", "afterDrain", "connected", "afterDrain"), log); job.cancel()
    }
}
