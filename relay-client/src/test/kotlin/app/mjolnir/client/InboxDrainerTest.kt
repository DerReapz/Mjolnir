package app.mjolnir.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class InboxDrainerTest {
    @Test fun drainHandlesEachDeliveryThenAcks() = runTest {
        val api = FakeRelayApi().apply { inbox += listOf(delivery("d1"), delivery("d2")) }
        val handled = mutableListOf<String>()
        assertEquals(2, InboxDrainer(api, MemorySeen()) { handled += it.id }.drain())
        assertEquals(listOf("d1", "d2"), handled); assertEquals(listOf("d1", "d2"), api.acked)
    }

    @Test fun seenDeliveryIsAckedButNotHandled() = runTest {
        val api = FakeRelayApi().apply { inbox += delivery("d1") }
        val seen = MemorySeen().apply { add("d1") }
        val handled = mutableListOf<String>()
        assertEquals(0, InboxDrainer(api, seen) { handled += it.id }.drain())
        assertEquals(emptyList(), handled); assertEquals(listOf("d1"), api.acked)
    }

    @Test fun concurrentDrainsHandleEachDeliveryOnce() = runTest {
        val api = FakeRelayApi().apply { inbox += delivery("d1") }; var count = 0
        val drainer = InboxDrainer(api, MemorySeen()) { yield(); count++ }
        listOf(async { drainer.drain() }, async { drainer.drain() }).awaitAll()
        assertEquals(1, count)
    }

    @Test fun failingHandlerDoesNotBlockOthersAndStaysUnacked() = runTest {
        val api = FakeRelayApi().apply { inbox += listOf(delivery("d1"), delivery("d2")) }
        val handled = mutableListOf<String>()
        val n = InboxDrainer(api, MemorySeen()) { if (it.id == "d1") error("boom"); handled += it.id }.drain()
        assertEquals(1, n)
        assertEquals(listOf("d2"), handled); assertEquals(listOf("d2"), api.acked)
    }

    // R12: a failed ack does not stop the drain; the next drain acks it without handling it again.
    @Test fun ackFailureDoesNotBlockOthersAndIsRetriedNextDrain() = runTest {
        val fake = FakeRelayApi().apply { inbox += listOf(delivery("d1"), delivery("d2")) }
        var failAck = true
        val api = object : RelayApi by fake {
            override suspend fun ack(deliveryId: String) {
                if (failAck && deliveryId == "d1") throw RelayException(RelayErrorKind.NETWORK, "down")
                fake.ack(deliveryId)
            }
        }
        val handled = mutableListOf<String>()
        val drainer = InboxDrainer(api, MemorySeen()) { handled += it.id }
        assertEquals(2, drainer.drain())
        assertEquals(listOf("d1", "d2"), handled); assertEquals(listOf("d2"), fake.acked)
        failAck = false
        assertEquals(0, drainer.drain())
        assertEquals(listOf("d1", "d2"), handled); assertEquals(listOf("d2", "d1"), fake.acked)
    }
}
