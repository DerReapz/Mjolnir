package app.mjolnir.client

import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatRulesTest {
    @Test fun capsAtCodePointsNeverSplittingASurrogatePair() {
        val capped = capCodePoints("a".repeat(3999) + "😀😀", 4000)
        assertEquals(4000, capped.codePointCount(0, capped.length))
        assertEquals("a".repeat(3999) + "😀", capped)
        assertEquals("short", capCodePoints("short", 4000))
    }

    @Test fun inviteCandidatesAreSpaceMembersNotInTheChat() {
        val me = Peer("me", "Me", DeviceKind.PC); val fred = Peer("fred", "Fred", DeviceKind.PHONE); val zed = Peer("zed", "Zed", DeviceKind.PC)
        val space = Space("s1", "Friends", "admin", listOf(Peer("admin", "Admin", DeviceKind.PC), me, fred, zed))
        assertEquals(listOf("admin", "zed"), inviteCandidates(space, listOf(me, fred), "me").map { it.deviceId })
    }

    @Test fun systemLinesAndErrorCopy() {
        val names = mapOf("sam" to "Sam", "ann" to "Ann")
        val nameOf = { id: String -> names[id] ?: "Someone" }
        assertEquals("Sam joined", Messages.systemLine(ChatEvent.MemberJoined("sam"), nameOf))
        assertEquals("Sam left", Messages.systemLine(ChatEvent.MemberLeft("sam"), nameOf))
        assertEquals("Ann · Removed by the space admin", Messages.systemLine(ChatEvent.MemberRemoved("ann", byAdmin = true), nameOf))
        assertEquals("Ann was removed", Messages.systemLine(ChatEvent.MemberRemoved("ann", byAdmin = false), nameOf))
        assertEquals("Someone is now the owner", Messages.systemLine(ChatEvent.OwnerChanged("x"), nameOf))
        assertEquals("Renamed to Trip", Messages.systemLine(ChatEvent.Renamed("Trip"), nameOf))
        assertEquals("Make someone else the owner first.", Messages.chatError(RelayException(RelayErrorKind.ALREADY_SENT, "owner_must_transfer")))
        assertEquals("Already invited.", Messages.chatError(RelayException(RelayErrorKind.ALREADY_SENT, "already_invited")))
        assertEquals("Already in this chat.", Messages.chatError(RelayException(RelayErrorKind.ALREADY_SENT, "already_member")))
        assertEquals("That chat is no longer there.", Messages.chatError(RelayException(RelayErrorKind.NOT_FOUND, "not_found")))
        assertEquals("Can't reach the relay. Check your connection.", Messages.chatError(RelayException(RelayErrorKind.NETWORK, "x")))
    }
}
