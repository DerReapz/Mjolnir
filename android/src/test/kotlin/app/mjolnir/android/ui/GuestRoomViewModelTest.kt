package app.mjolnir.android.ui

import app.mjolnir.android.MainDispatcherRule
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.android.data.TrayItem
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Nicknames
import app.mjolnir.client.Outbox
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.protocol.FileRef
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import app.mjolnir.client.GuestRoomController
import app.mjolnir.client.GuestRoomUi
import app.mjolnir.client.guestRoomSecretStore
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.RoomResponse
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class GuestRoomViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    private val api = FakeRelayApi()
    private lateinit var secrets: app.mjolnir.client.JsonFileStore<Map<String, String>>

    @Before fun setUp() { secrets = guestRoomSecretStore(File(temp.root, "guest-room.json")) }

    private fun newVm(): GuestRoomViewModel {
        val scope = kotlinx.coroutines.CoroutineScope(main.dispatcher)
        val controller = GuestRoomController({ api }, { "https://relay.example" }, secrets, scope) { 1_000L }
        return GuestRoomViewModel(controller, GuestTraffic(controller)) { api.events() }
    }

    @Test fun openShowsLinkAndShare() = runTest {
        val vm = newVm()
        vm.open(); advanceUntilIdle()
        val open = assertIs<GuestRoomUi.Open>(vm.state.value)
        assertEquals("https://relay.example/g/secret-1", open.link)
        assertEquals(1_000L + Limits.GUEST_ROOM_TTL_MS, open.expiresAtEpochMs)
        assertEquals("https://relay.example/g/secret-1", shareIntentText(open.link))
    }

    @Test fun restoreOnStart() = runTest {
        api.room = RoomResponse("r9", "s9", api.serverNowEpochMs + 60_000)
        secrets.write(mapOf("r9" to "s9"))
        val vm = newVm(); advanceUntilIdle()
        assertEquals("https://relay.example/g/s9", assertIs<GuestRoomUi.Open>(vm.state.value).link)
    }

    @Test fun endClears() = runTest {
        val vm = newVm()
        vm.open(); advanceUntilIdle()
        vm.end(); advanceUntilIdle()
        assertEquals(GuestRoomUi.Closed, vm.state.value)
        assertEquals(listOf("room-1"), api.endedRooms)
    }

    @Test fun eventsWhileCollectingShowGuestAndEnd() = runTest {
        val vm = newVm()
        vm.open(); advanceUntilIdle()
        val job = launch(main.dispatcher) { vm.collectEvents() }
        advanceUntilIdle()
        val guest = Peer("g1", "Guest", DeviceKind.GUEST)
        api.events.emit(RelayEvent.GuestJoined("room-1", guest)); advanceUntilIdle()
        assertEquals(guest, (vm.state.value as GuestRoomUi.Open).guest)
        api.events.emit(RelayEvent.RoomEnded("room-1")); advanceUntilIdle()
        assertEquals(GuestRoomUi.Closed, vm.state.value)
        job.cancel()
    }

    @Test fun sentRowIsMarkedDownloadedOnceAllItsFilesWere() = runTest {
        val controller = GuestRoomController({ api }, { "https://relay.example" }, secrets, kotlinx.coroutines.CoroutineScope(main.dispatcher)) { 1_000L }
        val traffic = GuestTraffic(controller)
        fun up(name: String, id: String) = LocalUpload("/x/$name", name, "text/plain", 3, ref = FileRef(id, name, 3, "text/plain", "0".repeat(64)))
        traffic.recordSent(OutgoingFiles("o1", "g1", "Guest", listOf(up("dir/a.txt", "f1"), up("dir/b.txt", "f2")), createdAtEpochMs = 0))
        val row = controller.exchanged.value.single()
        assertEquals(false, row.fromGuest)
        traffic.onEvent(RelayEvent.Downloaded("d", listOf("f1")))
        assertEquals(false, controller.exchanged.value.single().downloadedByGuest)
        traffic.onEvent(RelayEvent.Downloaded("d", listOf("f2")))
        assertEquals(true, controller.exchanged.value.single().downloadedByGuest)
    }

    @Test fun guestTargetAppearsInThrowTo() = runTest {
        api.peers += listOf(Peer("pc1", "Desktop", DeviceKind.PC), Peer("g1", "Guest", DeviceKind.GUEST))
        val throwing = ThrowViewModel(
            api,
            JsonFileStore(File(temp.root, "tray.json"), ListSerializer(TrayItem.serializer())) { emptyList() },
            JsonFileStore(File(temp.root, "config.json"), PhoneConfig.serializer()) { PhoneConfig("https://relay.example", deviceName = "Pixel") },
            Outbox(JsonFileStore(File(temp.root, "outbox.json"), ListSerializer(OutgoingFiles.serializer())) { emptyList() }, api = { api }, open = { u, _ -> File(u.path).inputStream() }),
            Nicknames(JsonFileStore(File(temp.root, "nicknames.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() }),
            {}, main.dispatcher,
        )
        throwing.refreshTargets(); advanceUntilIdle()
        val groups = throwing.state.value.groups
        assertEquals("Guest room", groups.first().title)
        assertEquals(listOf("g1"), groups.first().peers.map { it.deviceId })
        assertEquals("pc1", throwing.state.value.targetId)
    }
}
