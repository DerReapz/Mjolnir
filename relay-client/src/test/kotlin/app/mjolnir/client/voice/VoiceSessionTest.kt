package app.mjolnir.client.voice

import app.mjolnir.client.ClientTestScope
import app.mjolnir.client.TestDevice
import app.mjolnir.client.clientTest
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.server.RelayDeps
import app.mjolnir.server.RelayLimits
import app.mjolnir.server.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two sessions with fake audio over the real relay's voice socket. Timeouts are generous: CI is slow. */
class VoiceSessionTest {
    private val loudRms = 2_000.0 // the test tone is about 5 600 RMS
    private val quietRms = 100.0

    private class Party(val device: TestDevice, val mic: FakeMic, val speaker: FakeSpeaker, val session: VoiceSession, val sent: AtomicInteger)

    private suspend fun ClientTestScope.room(): Triple<TestDevice, TestDevice, String> {
        val a = newDevice("A", DeviceKind.PC); val b = newDevice("B", DeviceKind.PHONE)
        val space = a.api.createSpace("Friends")
        val claim = b.api.claimPairingCode(a.api.createInvite(space.id).code, CodeKind.SPACE_INVITE)
        a.api.acceptPairing(claim.pairingId)
        return Triple(a, b, a.api.voiceRooms(space.id).single { it.name == "Lounge" }.id)
    }

    private fun party(
        d: TestDevice, roomId: String, scope: CoroutineScope, openMic: Boolean, loud: Boolean = false,
        reconnectLimitMs: Long = 30_000, links: VoiceLinkFactory? = null,
    ): Party {
        val mic = FakeMic(loud); val speaker = FakeSpeaker(); val sent = AtomicInteger()
        val real = VoiceLinkFactory { onRoster, onFrame -> d.api.openVoice(roomId, onRoster, onFrame) }
        val counting = VoiceLinkFactory { onRoster, onFrame ->
            val link = (links ?: real).open(onRoster, onFrame)
            object : VoiceLink by link {
                override fun send(seq: Int, opus: ByteArray) { sent.incrementAndGet(); link.send(seq, opus) }
            }
        }
        val session = VoiceSession(counting, mic, speaker, scope, selfId = d.id, reconnectLimitMs = reconnectLimitMs)
        session.openMic = openMic
        return Party(d, mic, speaker, session, sent)
    }

    private suspend fun eventually(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        withTimeoutOrNull(timeoutMs) { while (!cond()) delay(20) }
        assertTrue(cond(), what)
    }

    private suspend fun VoiceSession.awaitState(s: VoiceState) = withTimeout(15_000) { state.first { it == s } }

    private fun voiceTest(
        deps: RelayDeps = RelayDeps(store = Store("jdbc:sqlite::memory:"), version = "1.0.0"),
        block: suspend ClientTestScope.(CoroutineScope) -> Unit,
    ) = clientTest(deps) {
        val scope = CoroutineScope(SupervisorJob())
        try { block(scope) } finally { scope.cancel() }
    }

    @Test fun toneReachesTheOtherSideAndShowsAsSpeaking() = voiceTest { scope ->
        val (a, b, room) = room()
        val pa = party(a, room, scope, openMic = false, loud = true)
        val pb = party(b, room, scope, openMic = true)
        try {
            pa.session.awaitState(VoiceState.Live); pb.session.awaitState(VoiceState.Live)
            pa.session.setTalking(true)
            eventually("A's tone reaches B") { pb.speaker.recentRms() > loudRms }
            eventually("B sees A speaking") { a.id in pb.session.speaking.value }
            eventually("A sees itself speaking") { a.id in pa.session.speaking.value }
            assertTrue(b.id !in pb.session.speaking.value, "B is silent")
            assertTrue(pa.speaker.recentRms() < quietRms, "A hears nothing of its own")
        } finally { pa.session.leave(); pb.session.leave() }
    }

    @Test fun muteStopsIt() = voiceTest { scope ->
        val (a, b, room) = room()
        val pa = party(a, room, scope, openMic = true, loud = true)
        val pb = party(b, room, scope, openMic = true)
        try {
            eventually("A's tone reaches B") { pb.speaker.recentRms() > loudRms }
            pa.session.setMuted(true)
            eventually("muted: B hears silence") { pb.speaker.recentRms() < quietRms }
            eventually("muted: A no longer speaking for B") { a.id !in pb.session.speaking.value }
            val sent = pa.sent.get(); delay(400)
            assertEquals(sent, pa.sent.get(), "a muted mic sends nothing")
            pa.session.setMuted(false)
            eventually("unmuted: back") { pb.speaker.recentRms() > loudRms }
        } finally { pa.session.leave(); pb.session.leave() }
    }

    @Test fun pushToTalkOffStopsIt() = voiceTest { scope ->
        val (a, b, room) = room()
        val pa = party(a, room, scope, openMic = false, loud = true)
        val pb = party(b, room, scope, openMic = true)
        try {
            pa.session.awaitState(VoiceState.Live); pb.session.awaitState(VoiceState.Live)
            delay(400)
            assertEquals(0, pa.sent.get(), "push to talk not held: nothing sent")
            pa.session.setTalking(true)
            eventually("held: B hears A") { pb.speaker.recentRms() > loudRms }
            pa.session.setTalking(false)
            eventually("released: silence") { pb.speaker.recentRms() < quietRms }
            eventually("released: not speaking") { a.id !in pb.session.speaking.value }
        } finally { pa.session.leave(); pb.session.leave() }
    }

    @Test fun openMicSendsNoSilentFrames() = voiceTest { scope ->
        val (a, b, room) = room()
        val pa = party(a, room, scope, openMic = true, loud = false)
        val pb = party(b, room, scope, openMic = true)
        try {
            pa.session.awaitState(VoiceState.Live); pb.session.awaitState(VoiceState.Live)
            delay(600)
            assertEquals(0, pa.sent.get(), "silence on open mic is not sent")
            pa.mic.loud = true
            eventually("speech is") { pb.speaker.recentRms() > loudRms }
        } finally { pa.session.leave(); pb.session.leave() }
    }

    @Test fun roomFullEndsWithThatReason() = voiceTest(
        RelayDeps(store = Store("jdbc:sqlite::memory:"), version = "1.0.0", limits = RelayLimits(maxVoicePerRoom = 1)),
    ) { scope ->
        val (a, b, room) = room()
        val pa = party(a, room, scope, openMic = true)
        try {
            pa.session.awaitState(VoiceState.Live)
            val pb = party(b, room, scope, openMic = true)
            pb.session.awaitState(VoiceState.Ended("room_full"))
        } finally { pa.session.leave() }
    }

    @Test fun leaveEndsAndStopsTheAudio() = voiceTest { scope ->
        val (a, b, room) = room()
        val pa = party(a, room, scope, openMic = true)
        pa.session.awaitState(VoiceState.Live)
        pa.session.leave()
        assertEquals(VoiceState.Ended(null), pa.session.state.value)
        eventually("mic and speaker stop") { pa.mic.stopped && pa.speaker.stopped }
        eventually("the relay removed A at once (no grace)") { deps.voice.participants(room).isEmpty() }
        assertTrue(b.id.isNotEmpty())
    }

    @Test fun droppedLinkReconnects() = voiceTest { scope ->
        val (a, b, room) = room()
        val pa = party(a, room, scope, openMic = true, loud = true)
        val pb = party(b, room, scope, openMic = true)
        val states = Collections.synchronizedList(mutableListOf<VoiceState>())
        scope.launch { pa.session.state.collect { states += it } }
        try {
            eventually("A's tone reaches B") { pb.speaker.recentRms() > loudRms }
            deps.voice.linkOf(a.id)!!.session.cancel() // the relay side of A's link goes away
            eventually("A reconnects") { VoiceState.Reconnecting in states && pa.session.state.value == VoiceState.Live }
            pb.speaker.frames.clear()
            eventually("audio flows again") { pb.speaker.recentRms() > loudRms }
        } finally { pa.session.leave(); pb.session.leave() }
    }

    @Test fun linkThatNeverComesBackEndsAsConnectionLost() = voiceTest { scope ->
        val (a, _, room) = room()
        val failing = CompletableDeferred<String?>().apply { complete(null) }
        var first = true
        val links = VoiceLinkFactory { onRoster, onFrame ->
            if (first) { first = false; a.api.openVoice(room, onRoster, onFrame) } else object : VoiceLink {
                override fun send(seq: Int, opus: ByteArray) {}
                override fun close() {}
                override val closeReason: Deferred<String?> = failing
            }
        }
        val pa = party(a, room, scope, openMic = true, reconnectLimitMs = 1_500, links = links)
        try {
            pa.session.awaitState(VoiceState.Live)
            deps.voice.linkOf(a.id)!!.session.cancel()
            pa.session.awaitState(VoiceState.Reconnecting)
            pa.session.awaitState(VoiceState.Ended("connection_lost"))
            eventually("audio stops") { pa.mic.stopped && pa.speaker.stopped }
        } finally { pa.session.leave() }
    }
}
