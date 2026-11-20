package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import kotlin.test.Test
import kotlin.test.assertEquals

class DestinationsTest {
    private fun p(id: String, name: String) = Peer(id, name, DeviceKind.PHONE)

    @Test fun singleGroupHasNoTitle() {
        val groups = groupDestinations(listOf(p("1", "A")), null)
        assertEquals(1, groups.size)
        assertEquals("mine", groups[0].key)
        assertEquals(null, groups[0].title)
    }

    @Test fun lastUsedFirstThenByName() {
        val groups = groupDestinations(listOf(p("1", "Zed"), p("2", "bob"), p("3", "Amy")), "1")
        assertEquals(listOf("1", "3", "2"), groups.single().peers.map { it.deviceId })
    }

    @Test fun sortsByTheShownNameWhenGiven() {
        val shown = mapOf("1" to "Zed", "2" to "Amy", "3" to "Bob") // nicknames; real names sort the other way
        val groups = groupDestinations(listOf(p("1", "Aaa"), p("2", "Bbb"), p("3", "Ccc")), null) { shown.getValue(it.deviceId) }
        assertEquals(listOf("2", "3", "1"), groups.single().peers.map { it.deviceId })
    }
}
