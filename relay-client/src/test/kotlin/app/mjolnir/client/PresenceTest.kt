package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Space
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresenceTest {
    private fun space(vararg members: Peer) = Space("s1", "Crew", "a", members.toList())

    @Test fun seedReadsMembersAndEventsUpdate() {
        val t = PresenceTracker()
        t.seed(listOf(space(Peer("a", "A", DeviceKind.PC, online = true), Peer("b", "B", DeviceKind.PHONE, lastSeenMs = 7))))
        assertEquals(Presence(true, null), t.presence.value["a"]); assertEquals(Presence(false, 7), t.presence.value["b"])
        t.onEvent(RelayEvent.PresenceChanged("b", true, null))
        assertTrue(t.presence.value.getValue("b").online)
        t.onEvent(RelayEvent.PresenceChanged("b", false, 99))
        assertEquals(Presence(false, 99), t.presence.value["b"])
        t.onEvent(RelayEvent.Connected) // unrelated events change nothing
        assertEquals(2, t.presence.value.size)
    }

    @Test fun seedReplacesWhatWasThere() {
        val t = PresenceTracker()
        t.seed(listOf(space(Peer("a", "A", DeviceKind.PC))))
        t.seed(listOf(space(Peer("b", "B", DeviceKind.PC))))
        assertEquals(setOf("b"), t.presence.value.keys)
    }
}
