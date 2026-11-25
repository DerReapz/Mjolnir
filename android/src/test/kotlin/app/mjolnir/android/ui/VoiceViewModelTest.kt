package app.mjolnir.android.ui

import app.mjolnir.android.MainDispatcherRule
import app.mjolnir.android.voice.phoneVoiceController
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.Messages
import app.mjolnir.client.voice.AudioInput
import app.mjolnir.client.voice.AudioOutput
import app.mjolnir.client.voice.VoiceAudioFactory
import app.mjolnir.client.voice.VoiceController
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.VoiceRoom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test

/** A silent microphone paced at one 20 ms frame per read, and a speaker that drops what it is given. */
internal class SilentAudio : VoiceAudioFactory {
    override fun input(): AudioInput = object : AudioInput {
        @Volatile var stopped = false
        override fun start() {}
        override fun read(into: ShortArray): Int {
            if (stopped) return -1
            Thread.sleep(20)
            into.fill(0)
            return into.size
        }
        override fun stop() { stopped = true }
    }

    override fun output(): AudioOutput = object : AudioOutput {
        override fun start() {}
        override fun write(pcm: ShortArray) {}
        override fun stop() {}
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val admin = Peer("adm", "Admin", DeviceKind.PC)

    private class Env(
        val fake: FakeRelayApi, val controller: VoiceController, val vm: VoiceViewModel, val started: MutableList<VoiceRoom>,
        val selfReads: java.util.concurrent.atomic.AtomicInteger, val serviceNotices: MutableSharedFlow<String>,
    )

    private fun test(mic: () -> Boolean = { true }, serviceStarts: Boolean = true, block: suspend Env.() -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val fake = FakeRelayApi()
        fake.asMemberOf("s1", admin, name = "Crew")
        val controller = phoneVoiceController(fake, scope, SilentAudio(), selfId = { fake.me.deviceId }, audioDispatcher = Dispatchers.IO)
        val started = java.util.Collections.synchronizedList(mutableListOf<VoiceRoom>())
        val selfReads = java.util.concurrent.atomic.AtomicInteger()
        val serviceNotices = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val vm = VoiceViewModel(
            controller, selfId = { selfReads.incrementAndGet(); fake.me.deviceId }, hasMicPermission = mic,
            startService = { started += it; serviceStarts }, serviceNotices = serviceNotices,
        )
        try { withTimeout(20_000) { Env(fake, controller, vm, started, selfReads, serviceNotices).block() } } finally { controller.leave(); scope.cancel() }
    }

    private suspend fun eventually(what: String, cond: () -> Boolean) {
        withTimeout(10_000) { while (!cond()) delay(10) }
        assertTrue(cond(), what)
    }

    @Test fun mapsRoomsPerSpaceAndTheActivePanelWithOpenMicByDefault() = test {
        val lounge = fake.addVoiceRoom("s1", "Lounge", creator = admin)
        val mine = fake.addVoiceRoom("s1", "Games")
        vm.spacesLoaded(fake.spaces())
        eventually("rooms listed") { vm.ui.value.rooms["s1"]?.map { it.name } == listOf("Lounge", "Games") }
        val ui = vm.ui.value
        assertTrue(ui.supported)
        assertEquals(listOf("Crew"), ui.spaces.map { it.name })
        // Rename and Delete only for the room's creator or the space admin (here: not the admin).
        assertFalse(ui.canManage(lounge))
        assertTrue(ui.canManage(mine))

        vm.join(lounge)
        assertEquals(listOf(lounge), started.toList()) // the foreground service starts with the join
        eventually("in the room") { vm.ui.value.active?.roomId == lounge.id }
        val active = vm.ui.value.active!!
        assertEquals("Lounge", active.roomName)
        assertTrue(active.openMic, "the phone talks on open mic by default")
        assertEquals(lounge.id, vm.ui.value.activeRoom?.id)

        vm.setMuted(true)
        eventually("muted") { vm.ui.value.active?.muted == true }
        vm.leave()
        eventually("left") { vm.ui.value.active == null }
    }

    @Test fun anAdminCanManageEveryRoomOfTheirSpace() = test {
        val space = fake.createSpace("Mine")
        val other = fake.addVoiceRoom(space.id, "Lounge", creator = admin)
        vm.spacesLoaded(fake.spaces())
        eventually("loaded") { vm.ui.value.spaces.size == 2 }
        assertTrue(vm.ui.value.canManage(other))
    }

    @Test fun joinWithoutPermissionAsksForItAndARefusalOffersSettings() = test(mic = { false }) {
        val lounge = fake.addVoiceRoom("s1", "Lounge")
        vm.spacesLoaded(fake.spaces())
        vm.join(lounge)
        assertEquals(VoiceEffect.RequestMicPermission, withTimeout(5_000) { vm.effects.first() })
        assertTrue(started.isEmpty(), "no service before the permission")
        assertNull(controller.active.value)

        vm.onMicPermission(granted = false)
        val prompt = vm.ui.value.micPrompt!!
        assertEquals(Messages.MIC_PERMISSION, prompt.text)
        assertEquals("Mjolnir needs the microphone for voice rooms.", prompt.text)
        assertEquals("Open settings", prompt.action)
        assertTrue(started.isEmpty())
        assertNull(controller.active.value)
        vm.dismissMicPrompt()
        assertNull(vm.ui.value.micPrompt)
    }

    @Test fun aGrantedPermissionStartsTheServiceThenJoins() = test(mic = { false }) {
        val lounge = fake.addVoiceRoom("s1", "Lounge")
        vm.spacesLoaded(fake.spaces())
        vm.join(lounge)
        withTimeout(5_000) { vm.effects.first() }
        vm.onMicPermission(granted = true)
        assertEquals(listOf(lounge), started.toList())
        eventually("joined") { controller.active.value?.roomId == lounge.id }
        assertNull(vm.ui.value.micPrompt)
    }

    @Test fun roomActionsUpdateTheListAndRefusalsBecomeTheNotice() = test {
        vm.spacesLoaded(fake.spaces())
        eventually("loaded") { vm.ui.value.rooms.containsKey("s1") }
        vm.createRoom("s1", "Games")
        eventually("created") { vm.ui.value.rooms["s1"]?.map { it.name } == listOf("Games") }
        val games = vm.ui.value.rooms["s1"]!!.single()
        vm.renameRoom(games, "Den")
        eventually("renamed") { vm.ui.value.rooms["s1"]?.map { it.name } == listOf("Den") }
        vm.deleteRoom(games)
        eventually("deleted") { vm.ui.value.rooms["s1"].orEmpty().isEmpty() }
        val theirs = fake.addVoiceRoom("s1", "Lounge", creator = admin)
        vm.deleteRoom(theirs) // only the creator or the admin may
        eventually("refused") { vm.ui.value.notice != null }
        vm.dismissNotice()
        assertNull(vm.ui.value.notice)
    }

    @Test fun anOldRelayHidesVoice() = test {
        fake.voiceRoomsOutdated = true
        vm.spacesLoaded(fake.spaces())
        eventually("hidden") { !vm.ui.value.supported }
    }

    @Test fun theUiFollowsTheSessionWithoutReReadingTheDeviceId() = test {
        val lounge = fake.addVoiceRoom("s1", "Lounge")
        vm.spacesLoaded(fake.spaces())
        eventually("self known") { vm.ui.value.selfId == fake.me.deviceId }
        val reads = selfReads.get()
        vm.join(lounge)
        eventually("in the room") { vm.ui.value.active?.roomId == lounge.id }
        repeat(5) { i ->
            vm.setMuted(i % 2 == 0)
            eventually("mute $i shown") { vm.ui.value.active?.muted == (i % 2 == 0) }
        }
        vm.setOpenMic(false)
        eventually("push to talk shown") { vm.ui.value.active?.openMic == false }
        assertEquals(reads, selfReads.get(), "session updates never read the device id again")
        assertTrue(vm.ui.value.canManage(lounge))
    }

    @Test fun aServiceThatCannotStartMeansNoVoice() = test(serviceStarts = false) {
        val lounge = fake.addVoiceRoom("s1", "Lounge")
        vm.spacesLoaded(fake.spaces())
        vm.join(lounge)
        assertEquals(listOf(lounge), started.toList())
        assertNull(controller.active.value, "never voice without the foreground service")
        assertEquals(Messages.VOICE_AUDIO_ERROR, vm.ui.value.notice)
    }

    @Test fun theServiceReportsWhyVoiceEnded() = test {
        serviceNotices.emit(Messages.VOICE_AUDIO_ERROR)
        eventually("shown") { vm.ui.value.notice == Messages.VOICE_AUDIO_ERROR }
    }

    @Test fun noChatsHintHidesWhenASpaceHasVoiceRooms() {
        val room = VoiceRoom("r1", "s1", "Lounge", "adm")
        val empty = ChatListUi()
        assertTrue(showNoChatsHint(empty, PhoneVoiceUi()))
        assertTrue(showNoChatsHint(empty, PhoneVoiceUi(supported = true, rooms = mapOf("s1" to emptyList()))))
        assertFalse(showNoChatsHint(empty, PhoneVoiceUi(supported = true, rooms = mapOf("s1" to listOf(room)))))
        // An old relay shows no voice: the hint stays.
        assertTrue(showNoChatsHint(empty, PhoneVoiceUi(supported = false, rooms = mapOf("s1" to listOf(room)))))
    }
}
