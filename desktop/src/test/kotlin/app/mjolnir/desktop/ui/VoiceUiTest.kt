@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.center
import androidx.compose.ui.test.runComposeUiTest
import app.mjolnir.client.Messages
import app.mjolnir.client.voice.ActiveVoice
import app.mjolnir.client.voice.VoiceState
import app.mjolnir.desktop.voice.bytesToPcm
import app.mjolnir.desktop.voice.pcmToBytes
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.VoiceRoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class VoiceUiTest {
    private val me = Peer("me", "This PC", DeviceKind.PC)
    private val sam = Peer("sam", "Sam", DeviceKind.PC)
    private val pixel = Peer("px", "Pixel 8", DeviceKind.PHONE)
    private val space = Space("s1", "Friends", "me", listOf(me, sam, pixel))
    private val lounge = VoiceRoom("r1", "s1", "Lounge", "me", listOf(sam, pixel))
    private val loungeWithMe = lounge.copy(participants = listOf(me, sam, pixel))

    private fun active(
        state: VoiceState = VoiceState.Live, speaking: Set<String> = emptySet(), muted: Boolean = false, talking: Boolean = false, openMic: Boolean = false,
    ) = ActiveVoice("r1", "Lounge", state, speaking, muted, talking, openMic)

    private fun voice(
        supported: Boolean = true, rooms: List<VoiceRoom> = listOf(lounge), active: ActiveVoice? = null, notice: String? = null,
    ) = VoiceUi(supported, listOf(space), mapOf("s1" to rooms), active, notice)

    private fun ComposeUiTest.show(voice: VoiceUi, log: MutableList<String> = mutableListOf()) = setContent {
        // The key handler lives on the window (MainWindow); this box stands in for it, holding the focus.
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        MjolnirTheme { Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { pushToTalkKey(it, voice.active, { t -> log += "talking $t" }) }) {
            ChatsTabContent(
                ChatsUi(emptyList(), emptyList(), emptyMap(), "me", emptySet(), null, emptyList(), emptyList(), { it.name }, notice = null, voice = voice),
                ChatsActions(voice = VoiceActions(
                    onJoin = { log += "join ${it.name}" }, onLeave = { log += "leave" }, onMute = { log += "mute $it" },
                    onTalking = { log += "talking $it" }, onOpenMic = { log += "openMic $it" },
                    onCreateRoom = { s, n -> log += "create $s $n" }, onDismissNotice = { log += "dismiss" },
                )),
            )
        } }
    }

    @Test fun spaceShowsVoiceRoomsWithParticipants() = runComposeUiTest {
        show(voice())
        onNodeWithText("Voice").assertExists()
        onNodeWithText("🔊 Lounge").assertExists()
        onNodeWithText("Sam, Pixel 8").assertExists()
        onNodeWithContentDescription("Lounge — Sam, Pixel 8").assertExists()
    }

    @Test fun clickingARoomJoinsIt() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(), log)
        onNodeWithText("🔊 Lounge").performClick()
        assertEquals(listOf("join Lounge"), log)
    }

    @Test fun hiddenWhenTheRelayHasNoVoice() = runComposeUiTest {
        show(voice(supported = false))
        onAllNodesWithText("Voice").assertCountEquals(0)
        onAllNodesWithText("🔊 Lounge").assertCountEquals(0)
    }

    @Test fun panelShowsParticipantsRingAndMuteIcon() = runComposeUiTest {
        show(voice(rooms = listOf(loungeWithMe), active = active(speaking = setOf("sam"), muted = true)))
        onNodeWithText("This PC").assertExists()
        onAllNodesWithContentDescription("Speaking").assertCountEquals(1)
        onAllNodesWithContentDescription("Muted").assertCountEquals(1)
    }

    @Test fun muteAndLeaveCallTheController() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(rooms = listOf(loungeWithMe), active = active()), log)
        onNodeWithText("Mute").performClick()
        onNodeWithText("Leave").performClick()
        assertEquals(listOf("mute true", "leave"), log)
    }

    @Test fun mutedShowsUnmute() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(rooms = listOf(loungeWithMe), active = active(muted = true)), log)
        onNodeWithText("Unmute").performClick()
        assertEquals(listOf("mute false"), log)
    }

    @Test fun talkingModeToggleAndHeadphonesHint() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(rooms = listOf(loungeWithMe), active = active()), log)
        onAllNodesWithText(Messages.USE_HEADPHONES).assertCountEquals(0)
        onNodeWithText("Open mic").performClick()
        assertEquals(listOf("openMic true"), log)
    }

    @Test fun openMicShowsHeadphonesHintAndNoTalkButton() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(rooms = listOf(loungeWithMe), active = active(openMic = true)), log)
        onNodeWithText("Use headphones to avoid echo").assertExists()
        onAllNodesWithText("Hold ` to talk").assertCountEquals(0)
        onNodeWithText("Push to talk").performClick()
        assertEquals(listOf("openMic false"), log)
    }

    @Test fun holdingTheButtonTalks() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(rooms = listOf(loungeWithMe), active = active()), log)
        onNodeWithText("Hold ` to talk").performTouchInput { down(center) }
        assertEquals(listOf("talking true"), log)
        onNodeWithText("Hold ` to talk").performTouchInput { up() }
        assertEquals(listOf("talking true", "talking false"), log)
    }

    @Test fun backtickKeyHoldsToTalk() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(rooms = listOf(loungeWithMe), active = active()), log)
        onRoot().performKeyInput { keyDown(Key.Grave) }
        assertEquals(listOf("talking true"), log)
        onRoot().performKeyInput { keyUp(Key.Grave) }
        assertEquals(listOf("talking true", "talking false"), log)
    }

    @Test fun backtickIsLeftAloneOnOpenMic() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(rooms = listOf(loungeWithMe), active = active(openMic = true)), log)
        onRoot().performKeyInput { keyDown(Key.Grave); keyUp(Key.Grave) }
        assertEquals(emptyList(), log)
    }

    @Test fun backtickIsLeftAloneOutOfVoice() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(), log)
        onRoot().performKeyInput { keyDown(Key.Grave); keyUp(Key.Grave) }
        assertEquals(emptyList(), log)
    }

    @Test fun backtickRepeatsDoNotCallAgainWhileTalking() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(rooms = listOf(loungeWithMe), active = active(talking = true)), log)
        onRoot().performKeyInput { keyDown(Key.Grave) }
        assertEquals(emptyList(), log)
    }

    @Test fun reconnectingAndEndedMessagesShow() = runComposeUiTest {
        show(voice(rooms = listOf(loungeWithMe), active = active(state = VoiceState.Reconnecting)))
        onNodeWithText("Reconnecting…").assertExists()
    }

    @Test fun endedMessageShowsAndDismisses() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(notice = "You left Lounge (connection lost)"), log)
        onNodeWithText("You left Lounge (connection lost)").assertExists()
        onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(listOf("dismiss"), log)
    }

    @Test fun audioErrorShowsInline() = runComposeUiTest {
        show(voice(notice = Messages.voiceEnded("audio_error", "Lounge")))
        onNodeWithText("Can't use the microphone or speaker.").assertExists()
    }

    @Test fun newVoiceRoomCreates() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(voice(), log)
        onNodeWithText("New voice room").performClick()
        onNode(hasSetTextAction()).performTextInput("Games")
        onNodeWithText("Create").performClick()
        assertEquals(listOf("create s1 Games"), log)
    }

    @Test fun pcmConvertsToLittleEndianBytesAndBack() {
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768, 0x1234)
        val bytes = pcmToBytes(pcm)
        assertEquals(0x34, bytes[10].toInt())
        assertEquals(0x12, bytes[11].toInt())
        val back = ShortArray(pcm.size)
        bytesToPcm(bytes, bytes.size, back)
        assertContentEquals(pcm, back)
    }

    @Test fun trayTooltipNamesTheRoom() {
        assertEquals("Mjolnir", trayTooltip("Mjolnir", null))
        assertEquals("In voice: Lounge", trayTooltip("Mjolnir", "Lounge"))
    }
}
