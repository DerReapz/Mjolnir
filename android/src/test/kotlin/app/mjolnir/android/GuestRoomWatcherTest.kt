package app.mjolnir.android

import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.GuestRoomController
import app.mjolnir.client.GuestRoomUi
import app.mjolnir.client.guestRoomSecretStore
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.RoomResponse
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class GuestRoomWatcherTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    private val api = FakeRelayApi()
    private val guest = Peer("g1", "Guest", DeviceKind.GUEST)
    private lateinit var controller: GuestRoomController
    private var joined = 0

    @Before fun setUp() {
        controller = GuestRoomController(
            { api }, { "https://relay.example" }, guestRoomSecretStore(File(temp.root, "guest-room.json")),
            CoroutineScope(main.dispatcher),
        ) { 1_000L }
    }

    private fun watch() = GuestRoomWatcher(controller, { api.events() }, CoroutineScope(main.dispatcher), { joined++ }, retryDelayMs = 10)

    @Test fun listensOnlyWhileARoomIsOpen() = runTest {
        watch(); advanceUntilIdle()
        assertEquals(0, api.events.subscriptionCount.value)
        controller.open(); advanceUntilIdle()
        assertEquals(1, api.events.subscriptionCount.value)
        api.events.emit(RelayEvent.RoomEnded("room-1")); advanceUntilIdle()
        assertEquals(GuestRoomUi.Closed, controller.state.value)
        assertEquals(0, api.events.subscriptionCount.value)
    }

    @Test fun guestJoiningShowsAndNotifiesOnceWithNoScreenOpen() = runTest {
        watch()
        controller.open(); advanceUntilIdle()
        api.events.emit(RelayEvent.GuestJoined("other-room", guest)); advanceUntilIdle()
        assertEquals(0, joined)
        api.events.emit(RelayEvent.GuestJoined("room-1", guest)); advanceUntilIdle()
        api.events.emit(RelayEvent.GuestJoined("room-1", guest)); advanceUntilIdle()
        assertEquals(guest, (controller.state.value as GuestRoomUi.Open).guest)
        assertEquals(1, joined)
    }

    @Test fun downloadsMarkSentRowsWithNoScreenOpen() = runTest {
        watch()
        controller.open(); advanceUntilIdle()
        controller.recordSent("out:o1:0", "a.jpg", "1 MB", setOf("f1"))
        api.events.emit(RelayEvent.Downloaded("d", listOf("f1"))); advanceUntilIdle()
        assertEquals(true, controller.exchanged.value.single().downloadedByGuest)
    }

    @Test fun connectedEventReplaysDownloadsMissedWhileAway() = runTest {
        watch()
        controller.open(); advanceUntilIdle()
        controller.recordSent("out:o1:0", "a.jpg", "1 MB", setOf("f1"))
        api.roomDownloaded = listOf("f1")
        api.events.emit(RelayEvent.Connected); advanceUntilIdle()
        assertEquals(true, controller.exchanged.value.single().downloadedByGuest)
    }

    @Test fun aRoomRestoredAtAppStartIsListenedTo() = runTest {
        api.room = RoomResponse("r9", "s9", api.serverNowEpochMs + 60_000)
        File(temp.root, "guest-room.json").writeText("""{"r9":"s9"}""")
        watch()
        controller.restore(); advanceUntilIdle()
        assertIs<GuestRoomUi.Open>(controller.state.value)
        assertEquals(1, api.events.subscriptionCount.value)
    }
}
