package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Tab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RelayEventsTest {
    @Test fun backoffDoublesAndCaps() =
        assertEquals(listOf(1_000L, 2_000L, 16_000L, 30_000L, 30_000L), listOf(0, 1, 4, 5, 10).map(::backoffMs))

    @Test fun emitsConnectedThenPairRequestedThenDelivery() = clientTest {
        val pc = newDevice("PC", DeviceKind.PC); val phone = newDevice("Pixel 8", DeviceKind.PHONE)
        val events = Channel<RelayEvent>(Channel.UNLIMITED)
        val job = launch { pc.api.events().collect { events.send(it) } }
        assertEquals(RelayEvent.Connected, withTimeout(5_000) { events.receive() }); awaitHubConnected(pc.id)
        val claim = phone.api.claimPairingCode(pc.api.createPairingCode().code)
        assertEquals("Pixel 8", (withTimeout(5_000) { events.receive() } as RelayEvent.PairRequested).from.name)
        pc.api.acceptPairing(claim.pairingId); assertIs<RelayEvent.Paired>(withTimeout(5_000) { events.receive() })
        phone.api.send(pc.id, listOf(Tab("https://a.com")))
        assertEquals("https://a.com", (withTimeout(5_000) { events.receive() } as RelayEvent.DeliveryArrived).delivery.tabs.single().url)
        job.cancel()
    }

    @Test fun reconnectsAndEmitsConnectedAgain() = fakeWsTest { client ->
        assertEquals(
            listOf(RelayEvent.Connected, RelayEvent.Unpaired("x"), RelayEvent.Connected, RelayEvent.Unpaired("x")),
            withTimeout(10_000) { client.events().take(4).toList() },
        )
    }

    @Test fun silentServerTriggersIdleReconnect() = silentWsTest { client ->
        assertEquals(
            listOf(RelayEvent.Connected, RelayEvent.Unpaired("x"), RelayEvent.Connected),
            withTimeout(10_000) { client.events().take(3).toList() },
        )
    }

    @Test fun unauthorizedStopsWithoutRetry() = clientTest {
        assertKind(RelayErrorKind.UNAUTHORIZED) { apiWithToken("bogus").events().collect { } }
    }

    @Test fun closeEndsActiveFlowCleanly() = clientTest {
        val pc = newDevice("PC", DeviceKind.PC)
        val events = Channel<RelayEvent>(Channel.UNLIMITED)
        val job = launch { pc.api.events().collect { events.send(it) } }
        assertEquals(RelayEvent.Connected, withTimeout(5_000) { events.receive() })
        pc.api.close()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCompleted && !job.isCancelled)
    }
}
