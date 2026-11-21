package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.RoomResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class GuestRoomTest {
    private val guest = Peer("g1", "Guest", DeviceKind.GUEST)

    private fun TestScope.controller(
        api: FakeRelayApi,
        url: String = "https://relay.example.com",
        clock: () -> Long = { 1_000L },
    ) = GuestRoomController({ api }, { url }, TestScope(UnconfinedTestDispatcher(testScheduler)), clock)

    @Test fun openBuildsLinkFromRelayUrl() = runTest {
        val api = FakeRelayApi()
        val c = controller(api)
        c.open()
        val open = assertIs<GuestRoomUi.Open>(c.state.value)
        assertEquals("room-1", open.roomId)
        assertEquals("https://relay.example.com/g/secret-1", open.link)
        assertEquals(1_000L + 3_600_000L, open.expiresAtEpochMs)
    }

    @Test fun trailingSlashInRelayUrlIsNotDoubled() = runTest {
        val c = controller(FakeRelayApi(), url = "https://relay.example.com/")
        c.open()
        assertEquals("https://relay.example.com/g/secret-1", assertIs<GuestRoomUi.Open>(c.state.value).link)
    }

    @Test fun guestJoinedSetsGuest() = runTest {
        val c = controller(FakeRelayApi())
        c.open()
        c.onEvent(RelayEvent.GuestJoined("room-1", guest))
        assertEquals(guest, assertIs<GuestRoomUi.Open>(c.state.value).guest)
    }

    @Test fun guestJoinedForAnotherRoomIsIgnored() = runTest {
        val c = controller(FakeRelayApi())
        c.open()
        c.onEvent(RelayEvent.GuestJoined("other", guest))
        assertEquals(null, assertIs<GuestRoomUi.Open>(c.state.value).guest)
    }

    @Test fun roomEndedReturnsClosed() = runTest {
        val c = controller(FakeRelayApi())
        c.open()
        c.onEvent(RelayEvent.RoomEnded("room-1"))
        assertEquals(GuestRoomUi.Closed, c.state.value)
    }

    @Test fun endEndsTheRoomOnTheRelay() = runTest {
        val api = FakeRelayApi()
        val c = controller(api)
        c.open()
        c.end()
        assertEquals(GuestRoomUi.Closed, c.state.value)
        assertEquals(listOf("room-1"), api.endedRooms)
    }

    @Test fun restoresOpenRoomOnStart() = runTest {
        val api = FakeRelayApi().apply {
            room = RoomResponse("room-9", "secret-9", 5_000L)
            roomGuest = guest
            serverNowEpochMs = 2_000L
        }
        val c = controller(api)
        c.restore()
        val open = assertIs<GuestRoomUi.Open>(c.state.value)
        assertEquals("room-9", open.roomId)
        assertEquals(guest, open.guest)
        assertEquals(1_000L + 3_000L, open.expiresAtEpochMs)
        // The relay never returns the secret again, so a room restored after a restart has no link.
        assertEquals("", open.link)
    }

    @Test fun restoreKeepsTheLinkOfTheRoomThisSessionOpened() = runTest {
        val api = FakeRelayApi()
        val c = controller(api)
        c.open()
        c.restore()
        assertEquals("https://relay.example.com/g/secret-1", assertIs<GuestRoomUi.Open>(c.state.value).link)
    }

    @Test fun restoreWithNoRoomStaysClosed() = runTest {
        val c = controller(FakeRelayApi())
        c.restore()
        assertEquals(GuestRoomUi.Closed, c.state.value)
    }

    @Test fun countdownIgnoresClockSkew() = runTest {
        // The relay has 3 minutes left; the device clock is a day off, and still the countdown is 3 minutes from now.
        val api = FakeRelayApi().apply {
            room = RoomResponse("room-9", "secret-9", 1_700_000_180_000L)
            serverNowEpochMs = 1_700_000_000_000L
        }
        val c = controller(api, clock = { 86_400_000L })
        c.restore()
        assertEquals(86_400_000L + 180_000L, assertIs<GuestRoomUi.Open>(c.state.value).expiresAtEpochMs)
    }

    @Test fun downloadedEventMarksExchanged() = runTest {
        val c = controller(FakeRelayApi())
        c.open()
        c.record(Exchanged("f1", false, "a.jpg", "1 MB", null, false, null))
        c.record(Exchanged("f2", false, "b.jpg", "1 MB", null, false, null))
        c.onEvent(RelayEvent.Downloaded("del-1", listOf("f1")))
        assertEquals(listOf(true, false), c.exchanged.value.map { it.downloadedByGuest })
    }

    @Test fun secondOpenWhileOpenIsNoop() = runTest {
        val api = FakeRelayApi()
        val c = controller(api)
        c.open()
        val first = c.state.value
        c.open()
        assertEquals(first, c.state.value)
        assertEquals("room-1", api.room?.roomId)
    }

    @Test fun roomOpenElsewhereIsAdoptedNotFailed() = runTest {
        val api = FakeRelayApi().apply { room = RoomResponse("room-9", "secret-9", 5_000L); serverNowEpochMs = 2_000L }
        val c = controller(api)
        c.open()
        assertEquals("room-9", assertIs<GuestRoomUi.Open>(c.state.value).roomId)
    }

    @Test fun failedOpenShowsFailed() = runTest {
        val api = FakeRelayApi().apply { failAllWith = RelayException(RelayErrorKind.NETWORK, "down") }
        val c = controller(api)
        c.open()
        assertIs<GuestRoomUi.Failed>(c.state.value)
    }
}
