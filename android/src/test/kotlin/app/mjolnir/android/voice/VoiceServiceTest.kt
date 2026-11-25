package app.mjolnir.android.voice

import app.mjolnir.android.ui.SilentAudio
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.Messages
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VoiceServiceTest {
    @Test fun theNotificationOffersMuteOrUnmuteAndLeave() {
        assertEquals("Mjolnir voice — Lounge", voiceNotificationTitle("Lounge"))
        assertEquals(
            listOf(Messages.MUTE to ACTION_VOICE_MUTE, Messages.LEAVE_VOICE to ACTION_VOICE_LEAVE),
            voiceNotificationActions(muted = false),
        )
        assertEquals(
            listOf(Messages.UNMUTE to ACTION_VOICE_UNMUTE, Messages.LEAVE_VOICE to ACTION_VOICE_LEAVE),
            voiceNotificationActions(muted = true),
        )
    }

    @Test fun notificationActionsMuteUnmuteAndLeave() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val fake = FakeRelayApi()
        fake.asMemberOf("s1", Peer("adm", "Admin", DeviceKind.PC))
        val voice = phoneVoiceController(fake, scope, SilentAudio(), selfId = { fake.me.deviceId }, audioDispatcher = Dispatchers.IO)
        try {
            val lounge = fake.addVoiceRoom("s1", "Lounge")
            voice.join(lounge)
            assertTrue(applyVoiceAction(ACTION_VOICE_MUTE, voice))
            assertEquals(true, voice.active.value?.muted)
            assertTrue(applyVoiceAction(ACTION_VOICE_UNMUTE, voice))
            assertEquals(false, voice.active.value?.muted)
            assertFalse(applyVoiceAction("something.else", voice))
            assertFalse(applyVoiceAction(null, voice))
            assertEquals(lounge.id, voice.active.value?.roomId)
            assertTrue(applyVoiceAction(ACTION_VOICE_LEAVE, voice))
            assertNull(voice.active.value)
        } finally {
            voice.leave(); scope.cancel()
        }
    }

    @Test fun theCommunicationModeIsSetOnceAndRestoredWhenTheLastDeviceCloses() {
        val calls = mutableListOf<Boolean>()
        val mode = CommunicationMode { calls += it }
        val input = mode.claim()
        val output = mode.claim()
        assertEquals(listOf(true), calls)
        input.release()
        input.release() // a second release is ignored
        assertEquals(listOf(true), calls)
        output.release()
        assertEquals(listOf(true, false), calls)
        mode.claim().release()
        assertEquals(listOf(true, false, true, false), calls)
    }
}
