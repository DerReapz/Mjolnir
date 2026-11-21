package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.SpaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private fun sp(id: String, name: String, space: String, spaceName: String) =
        Peer(id, name, DeviceKind.PHONE, SpaceRef(space, spaceName))

    @Test fun spaceGroupsAfterMine() {
        val groups = groupDestinations(
            listOf(sp("3", "Sam", "s2", "Work"), p("1", "Zed"), sp("2", "Amy", "s1", "Friends"), sp("4", "Bea", "s1", "Friends")),
            lastUsedId = "4",
        )
        assertEquals(listOf("mine", "space:s2", "space:s1"), groups.map { it.key })
        assertEquals(listOf("My devices", "Work", "Friends"), groups.map { it.title })
        assertEquals(listOf("4", "2"), groups[2].peers.map { it.deviceId })
    }

    @Test fun pairedAndSpaceMemberOnlyUnderMine() {
        // The relay lists a device paired with you once, without a space; no space entry accompanies it.
        val groups = groupDestinations(listOf(p("1", "Zed"), sp("2", "Amy", "s1", "Friends")), null)
        assertEquals(listOf("1"), groups.first { it.key == "mine" }.peers.map { it.deviceId })
        assertEquals(listOf("2"), groups.first { it.key == "space:s1" }.peers.map { it.deviceId })
    }

    @Test fun deviceInTwoSpacesInBoth() {
        val groups = groupDestinations(listOf(sp("2", "Amy", "s1", "Friends"), sp("2", "Amy", "s2", "Work")), null)
        assertEquals(listOf("space:s1", "space:s2"), groups.map { it.key })
        assertEquals(listOf(DestinationKey("2", "s1")), groups[0].peers.map { it.destinationKey })
        assertEquals(listOf(DestinationKey("2", "s2")), groups[1].peers.map { it.destinationKey })
    }

    @Test fun emptyGroupsAreDropped() {
        assertEquals(emptyList(), groupDestinations(emptyList(), null))
        assertEquals(listOf("mine"), groupDestinations(listOf(p("1", "A")), null).map { it.key })
    }

    @Test fun isPairedMeansNoSpace() {
        assertTrue(p("1", "A").isPaired)
        assertFalse(sp("2", "B", "s1", "Friends").isPaired)
        // A guest has no space, but it is not a pairing either.
        assertFalse(Peer("g", "Guest", DeviceKind.GUEST).isPaired)
        assertTrue(DestinationKey("1", null).isPaired)
        assertFalse(DestinationKey("2", "s1").isPaired)
    }

    @Test fun aGuestFormsItsOwnGroupFirst() {
        val groups = groupDestinations(listOf(p("1", "A"), Peer("g", "Guest", DeviceKind.GUEST), sp("2", "B", "s1", "Friends")), null)
        assertEquals(listOf("guest", "mine", "space:s1"), groups.map { it.key })
        assertEquals("Guest room", groups.first().title)
        assertEquals(listOf("g"), groups.first().peers.map { it.deviceId })
        assertEquals(listOf("1"), groups[1].peers.map { it.deviceId })
    }

    @Test fun aLoneGuestKeepsItsTitle() {
        val groups = groupDestinations(listOf(Peer("g", "Guest", DeviceKind.GUEST)), null)
        assertEquals(listOf("Guest room"), groups.map { it.title })
    }
}
