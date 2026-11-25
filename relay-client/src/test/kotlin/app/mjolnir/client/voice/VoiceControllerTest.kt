package app.mjolnir.client.voice

import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.FakeVoiceLink
import app.mjolnir.client.Messages
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.VoiceRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceControllerTest {
    private val admin = Peer("adm", "Admin", DeviceKind.PC)

    private class Audio : VoiceAudioFactory {
        val mics = mutableListOf<FakeMic>(); val speakers = mutableListOf<FakeSpeaker>()
        override fun input(): AudioInput = FakeMic().also { mics += it }
        override fun output(): AudioOutput = FakeSpeaker().also { speakers += it }
    }

    private fun test(block: suspend CoroutineScope.(FakeRelayApi, VoiceController, Audio) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val fake = FakeRelayApi()
        fake.asMemberOf("s1", admin); fake.asMemberOf("s2", admin)
        val audio = Audio()
        val controller = VoiceController(fake, scope, audio, selfId = { fake.me.deviceId })
        try { withTimeout(20_000) { block(fake, controller, audio) } } finally { scope.cancel() }
    }

    private suspend fun eventually(what: String, cond: () -> Boolean) {
        withTimeout(10_000) { while (!cond()) delay(10) }
        assertTrue(cond(), what)
    }

    @Test fun roomsLoadPerSpace() = test { fake, c, _ ->
        fake.addVoiceRoom("s1", "Lounge"); fake.addVoiceRoom("s2", "Lounge"); fake.addVoiceRoom("s2", "Games")
        c.refresh(fake.spaces())
        assertEquals(listOf("Lounge"), c.rooms.value["s1"]!!.map { it.name })
        assertEquals(listOf("Lounge", "Games"), c.rooms.value["s2"]!!.map { it.name })
        assertTrue(c.supported.value)
    }

    @Test fun eventsUpdateTheLists() = test { fake, c, _ ->
        val lounge = fake.addVoiceRoom("s1", "Lounge")
        c.refresh(fake.spaces())
        val games = VoiceRoom("r-games", "s1", "Games", admin.deviceId)
        c.onEvent(RelayEvent.VoiceRoomChanged(games))
        assertEquals(listOf("Lounge", "Games"), c.rooms.value["s1"]!!.map { it.name })
        c.onEvent(RelayEvent.VoiceRoomChanged(lounge.copy(name = "Den", participants = listOf(admin))))
        assertEquals(listOf("Den", "Games"), c.rooms.value["s1"]!!.map { it.name })
        assertEquals(listOf(admin), c.rooms.value["s1"]!!.first().participants)
        c.onEvent(RelayEvent.VoiceRoomDeleted("s1", "r-games"))
        assertEquals(listOf("Den"), c.rooms.value["s1"]!!.map { it.name })
        // On (re)connect the lists are fetched again.
        fake.addVoiceRoom("s1", "Late")
        c.onEvent(RelayEvent.Connected)
        eventually("refetched") { c.rooms.value["s1"]?.map { it.name } == listOf("Lounge", "Late") }
    }

    @Test fun createRenameDeleteUpdateTheList() = test { fake, c, _ ->
        fake.addVoiceRoom("s1", "Lounge")
        c.refresh(fake.spaces())
        val games = c.createRoom("s1", "Games")
        assertEquals(listOf("Lounge", "Games"), c.rooms.value["s1"]!!.map { it.name })
        c.renameRoom(games.id, "Cards")
        assertEquals(listOf("Lounge", "Cards"), c.rooms.value["s1"]!!.map { it.name })
        c.deleteRoom(games.id)
        assertEquals(listOf("Lounge"), c.rooms.value["s1"]!!.map { it.name })
    }

    @Test fun joinAndLeaveChangeActive() = test { fake, c, audio ->
        val lounge = fake.addVoiceRoom("s1", "Lounge"); val games = fake.addVoiceRoom("s1", "Games")
        c.refresh(fake.spaces())
        assertNull(c.active.value)
        c.join(lounge)
        assertEquals(lounge.id, c.active.value?.roomId)
        assertEquals("Lounge", c.active.value?.roomName)
        assertEquals(VoiceState.Connecting, c.active.value?.state)
        assertFalse(c.active.value!!.openMic, "push to talk by default on PC")
        val link = fake.voiceLinks.single()
        link.roster(listOf(fake.me.deviceId))
        eventually("live") { c.active.value?.state == VoiceState.Live }
        c.setMuted(true); assertTrue(c.active.value!!.muted)
        c.setTalking(true); assertTrue(c.active.value!!.talking)
        c.setOpenMic(true); assertTrue(c.active.value!!.openMic)
        // Joining another room leaves the first.
        c.join(games)
        assertTrue(link.closed, "the first room was left")
        assertEquals(games.id, c.active.value?.roomId)
        assertTrue(c.active.value!!.openMic, "the talking mode carries over")
        assertFalse(c.active.value!!.muted, "a new room starts unmuted")
        c.leave()
        assertNull(c.active.value)
        assertTrue(fake.voiceLinks.last().closed)
        eventually("audio stopped") { audio.mics.all { it.stopped } && audio.speakers.all { it.stopped } }
    }

    @Test fun relayEndingTheSessionClearsActiveWithANotice() = test { fake, c, _ ->
        val lounge = fake.addVoiceRoom("s1", "Lounge")
        c.refresh(fake.spaces())
        val notice = async(start = CoroutineStart.UNDISPATCHED) { c.notices.first() }
        c.join(lounge)
        fake.voiceLinks.single().roster(listOf(fake.me.deviceId))
        eventually("live") { c.active.value?.state == VoiceState.Live }
        fake.voiceLinks.single().drop("room_deleted")
        eventually("ended") { c.active.value == null }
        assertEquals(Messages.voiceEnded("room_deleted", "Lounge"), notice.await())
    }

    @Test fun roomFullNoticeUsesTheRelaysLimit() = test { fake, c, _ ->
        val lounge = fake.addVoiceRoom("s1", "Lounge")
        c.refresh(fake.spaces())
        val others = List(4) { Peer("p$it", "P$it", DeviceKind.PHONE) }
        c.onEvent(RelayEvent.VoiceRoomChanged(lounge.copy(participants = others, maxParticipants = 4)))
        val notice = async(start = CoroutineStart.UNDISPATCHED) { c.notices.first() }
        c.join(lounge)
        fake.voiceLinks.single().drop("room_full")
        assertEquals("Lounge is full (4/4)", notice.await())
        // A room the controller has not listed: never "0/0".
        val other = VoiceRoom("unlisted", "s2", "Den", admin.deviceId)
        val second = async(start = CoroutineStart.UNDISPATCHED) { c.notices.first() }
        c.join(other)
        fake.voiceLinks.last().drop("room_full")
        assertEquals("Den is full (8/8)", second.await())
    }

    @Test fun sessionsUseTheGivenAudioThreads() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val count = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "voice-audio-${count.incrementAndGet()}").apply { isDaemon = true } }
        try {
            val fake = FakeRelayApi(); fake.asMemberOf("s1", admin)
            val audio = Audio()
            val c = VoiceController(fake, scope, audio, audioDispatcher = pool.asCoroutineDispatcher())
            c.join(fake.addVoiceRoom("s1", "Lounge"))
            eventually("capture started") { audio.mics.single().startThread != null }
            assertTrue(audio.mics.single().startThread!!.startsWith("voice-audio-"), audio.mics.single().startThread)
            c.leave()
        } finally { scope.cancel(); pool.shutdownNow() }
    }

    @Test fun oldRelayHidesVoice() = test { fake, c, _ ->
        fake.addVoiceRoom("s1", "Lounge")
        c.refresh(fake.spaces())
        assertTrue(c.supported.value)
        fake.voiceRoomsOutdated = true
        c.refresh(fake.spaces())
        assertFalse(c.supported.value)
        assertTrue(c.rooms.value.isEmpty())
    }

    @Test fun phoneDefaultsToOpenMic() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val fake = FakeRelayApi(); fake.asMemberOf("s1", admin)
            val c = VoiceController(fake, scope, Audio(), openMicByDefault = true)
            c.join(fake.addVoiceRoom("s1", "Lounge"))
            assertTrue(c.active.value!!.openMic)
            c.leave()
        } finally { scope.cancel() }
    }
}
