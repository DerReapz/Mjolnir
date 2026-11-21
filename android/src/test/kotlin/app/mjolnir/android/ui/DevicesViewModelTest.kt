package app.mjolnir.android.ui

import app.mjolnir.android.MainDispatcherRule
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.android.session.PhoneSession
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Nicknames
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.SpaceRef
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val api = FakeRelayApi()
    private lateinit var nicknames: Nicknames
    private lateinit var vm: DevicesViewModel

    @Before fun setUp() {
        nicknames = Nicknames(JsonFileStore(File(tmp.root, "nicknames.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() })
        val config = JsonFileStore(File(tmp.root, "config.json"), serializer<PhoneConfig>()) { PhoneConfig("https://relay.example", deviceName = "Pixel") }
        vm = DevicesViewModel(api, PhoneSession(api, config, main.dispatcher), nicknames)
    }

    @Test fun onlyPairedDevicesAreListed() = runTest {
        api.peers += Peer("pc1", "Desk", DeviceKind.PC)
        api.peers += Peer("zed", "Zed", DeviceKind.PHONE, SpaceRef("s1", "Friends"))
        vm.refresh(); advanceUntilIdle()
        assertEquals(listOf("pc1"), vm.state.value.peers.map { it.deviceId })
    }

    @Test fun unpairingKeepsTheNicknameWhileASharedSpaceStillLinksTheDevice() = runTest {
        nicknames.set("pc1", "Work"); nicknames.set("pc2", "Home")
        api.peers += Peer("pc1", "Desk", DeviceKind.PC)
        api.peers += Peer("pc2", "Laptop", DeviceKind.PC)
        // the relay lists pc1 once more, through a space, after the pairing is gone
        api.peers += Peer("pc1", "Desk", DeviceKind.PC, SpaceRef("s1", "Friends"))
        vm.unpair("pc2"); advanceUntilIdle()
        assertEquals(mapOf("pc1" to "Work"), nicknames.changes.value)
        vm.unpair("pc1"); advanceUntilIdle() // removes every listing in the fake: nothing links it now
        assertEquals(emptyMap(), nicknames.changes.value)
    }
}
