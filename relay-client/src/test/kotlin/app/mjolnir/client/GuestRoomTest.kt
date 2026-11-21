package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.RoomResponse
import app.mjolnir.protocol.RoomStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class GuestRoomTest {
    private val guest = Peer("g1", "Guest", DeviceKind.GUEST)

    private val secrets = JsonFileStore(
        File.createTempFile("guest-room", ".json").also { it.delete(); it.deleteOnExit() },
        MapSerializer(String.serializer(), String.serializer()),
    ) { emptyMap() }

    private fun TestScope.controller(
        api: FakeRelayApi,
        url: String = "https://relay.example.com",
        clock: () -> Long = { 1_000L },
        store: JsonFileStore<Map<String, String>> = secrets,
        scope: CoroutineScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
    ) = GuestRoomController({ api }, { url }, store, scope, clock)

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
        secrets.write(mapOf("room-9" to "secret-9"))
        val c = controller(api)
        c.restore()
        val open = assertIs<GuestRoomUi.Open>(c.state.value)
        assertEquals("room-9", open.roomId)
        assertEquals(guest, open.guest)
        assertEquals(1_000L + 3_000L, open.expiresAtEpochMs)
        assertEquals("https://relay.example.com/g/secret-9", open.link)
    }

    @Test fun restoreSurvivesANewController() = runTest {
        val api = FakeRelayApi()
        controller(api).open()
        val second = controller(api)
        second.restore()
        assertEquals("https://relay.example.com/g/secret-1", assertIs<GuestRoomUi.Open>(second.state.value).link)
    }

    @Test fun restoreWithoutStoredSecretStaysClosedAndClearsLeftovers() = runTest {
        val api = FakeRelayApi().apply { room = RoomResponse("room-9", "secret-9", 5_000L); serverNowEpochMs = 2_000L }
        secrets.write(mapOf("old" to "stale"))
        val c = controller(api)
        c.restore()
        assertEquals(GuestRoomUi.Closed, c.state.value)
        assertEquals(emptyMap(), secrets.read())
    }

    @Test fun restoreEndsARoomWhoseSecretWasLost() = runTest {
        val api = FakeRelayApi().apply { room = RoomResponse("room-9", "secret-9", 5_000L); serverNowEpochMs = 2_000L }
        val c = controller(api)
        c.restore()
        assertEquals(GuestRoomUi.Closed, c.state.value)
        assertEquals(listOf("room-9"), api.endedRooms)
        assertEquals(null, api.room)
    }

    @Test fun openReplacesARoomWhoseSecretWasLost() = runTest {
        val api = FakeRelayApi().apply { room = RoomResponse("room-9", "secret-9", 5_000L); serverNowEpochMs = 2_000L }
        val c = controller(api)
        c.open()
        assertEquals(listOf("room-9"), api.endedRooms)
        val open = assertIs<GuestRoomUi.Open>(c.state.value)
        assertEquals("room-1", open.roomId)
        assertEquals("https://relay.example.com/g/secret-1", open.link)
    }

    @Test fun restoreWithNoRoomClearsStoredSecrets() = runTest {
        secrets.write(mapOf("old" to "stale"))
        controller(FakeRelayApi()).restore()
        assertEquals(emptyMap(), secrets.read())
    }

    @Test fun secretRemovedWhenRoomEnds() = runTest {
        val c = controller(FakeRelayApi())
        c.open()
        assertEquals(mapOf("room-1" to "secret-1"), secrets.read())
        c.onEvent(RelayEvent.RoomEnded("room-1"))
        assertEquals(emptyMap(), secrets.read())
        c.open()
        c.end()
        assertEquals(emptyMap(), secrets.read())
    }

    @Test fun failedEndKeepsTheRoomOpenWithTheError() = runTest {
        val api = FakeRelayApi()
        val c = controller(api)
        c.open()
        val before = assertIs<GuestRoomUi.Open>(c.state.value)
        api.failAllWith = RelayException(RelayErrorKind.NETWORK, "down")
        c.end()
        val after = assertIs<GuestRoomUi.Open>(c.state.value)
        assertEquals(before.link, after.link)
        assertEquals(before.expiresAtEpochMs, after.expiresAtEpochMs)
        assertEquals("Couldn't end the guest room", after.error)
        assertEquals(mapOf("room-1" to "secret-1"), secrets.read())
    }

    @Test fun staleRestoreDoesNotOverrideAnOpenThatStartedAfterIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeRelayApi().apply { currentRoomGate = gate }
        val c = controller(api)
        c.restore() // reads "no room", then waits on the gate
        c.open()
        val opened = assertIs<GuestRoomUi.Open>(c.state.value)
        gate.complete(Unit)
        assertEquals(opened, c.state.value)
        assertEquals(mapOf("room-1" to "secret-1"), secrets.read())
    }

    @Test fun staleRestoreDoesNotBringBackAnEndedRoom() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeRelayApi().apply {
            room = RoomResponse("room-9", "secret-9", 5_000L); serverNowEpochMs = 2_000L; currentRoomGate = gate
        }
        secrets.write(mapOf("room-9" to "secret-9"))
        val c = controller(api)
        c.restore() // reads room-9 open, then waits on the gate
        c.onEvent(RelayEvent.RoomEnded("room-9"))
        gate.complete(Unit)
        assertEquals(GuestRoomUi.Closed, c.state.value)
        assertEquals(emptyMap(), secrets.read())
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
        secrets.write(mapOf("room-9" to "secret-9"))
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
        secrets.write(mapOf("room-9" to "secret-9"))
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
