@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javafx.scene.media.MediaException
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A [VideoEngine] that plays nothing and records what the bubble asked (ruling CV-P3: no JavaFX in tests). */
internal class FakeEngine(
    private val failLoad: Throwable? = null, private val length: Long = 42_000, private val failRelease: Throwable? = null,
    /** Holds release's completion back until [finishRelease], as JavaFX disposes later on its own thread. */
    private val holdRelease: Boolean = false,
) : VideoEngine {
    override val positionMs = MutableStateFlow(0L)
    override val durationMs = MutableStateFlow<Long?>(null)
    override val error = MutableStateFlow<String?>(null)
    override val playing = MutableStateFlow(false)
    @Volatile var loaded: File? = null
    @Volatile var released = false
    override fun load(file: File) {
        failLoad?.let { throw it }
        loaded = file; durationMs.value = length
    }
    override fun play() { check(!released) { "played after release" }; playing.value = true }
    override fun pause() { playing.value = false }
    override fun seek(ms: Long) { positionMs.value = ms }
    private var onReleased: (() -> Unit)? = null
    override fun release(onReleased: () -> Unit) {
        released = true; playing.value = false
        failRelease?.let { throw it }
        if (holdRelease) this.onReleased = onReleased else onReleased()
    }
    fun finishRelease() { onReleased?.invoke(); onReleased = null }
}

/** Hands out [FakeEngine]s from [make] and draws a stand-in picture. */
internal class FakeVideo(private val make: () -> FakeEngine = { FakeEngine() }) : VideoSupport {
    val engines = mutableListOf<FakeEngine>()
    val opened = mutableListOf<Pair<String, File>>()
    override fun engine(): VideoEngine = make().also { synchronized(engines) { engines += it } }
    @Composable override fun Picture(engine: VideoEngine, modifier: Modifier) =
        Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF3B4A5A), Color(0xFF8A6A3A)))))
    override fun openInPlayer(att: Attachment, file: File) { opened += att.fileId to file }
}

/** Video files wait for [gate] (reporting [progress] first); thumbnails come at once. Records pins. */
internal class VideoMedia(private val thumbs: Map<String, File>, private val files: Map<String, File>) : ChatMediaSource {
    /** Completed: files come at once. Replace it to hold a download back. */
    var gate = CompletableDeferred(Unit)
    var progress: Pair<Long, Long>? = null
    val pinned = mutableListOf<String>()
    override suspend fun thumbnail(att: Attachment): File? = att.thumbFileId?.let(thumbs::get)
    override suspend fun file(att: Attachment): File = files.getValue(att.fileId)
    override suspend fun video(att: Attachment, onProgress: (Long, Long) -> Unit): File {
        progress?.let { (d, t) -> onProgress(d, t) }
        gate.await()
        return file(att)
    }
    override fun pin(fileId: String) { synchronized(pinned) { pinned += fileId } }
    override fun unpin(fileId: String) { synchronized(pinned) { pinned -= fileId } }
    fun pins() = synchronized(pinned) { pinned.toList() }
}

class VideoBubbleTest {
    private val me = Peer("me", "This PC", DeviceKind.PC)
    private val fred = Peer("fred", "Fred's phone", DeviceKind.PHONE)
    private val at = 1_700_000_000_000L
    private val dir: File = Files.createTempDirectory("video-bubbles").toFile().apply { deleteOnExit() }
    private val thumbFile = File(dir, "thumb.png").also { ImageIO.write(BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB), "png", it) }
    private val clipFile = File(dir, "v1").apply { writeBytes(ByteArray(100)) }

    private fun video(id: String = "v1", w: Int? = 1920, h: Int? = 1080, durationMs: Long? = 42_000, thumb: String? = "t1", name: String = "clip.mp4") =
        Attachment(id, AttachmentKind.VIDEO, name, 100, "video/mp4", w, h, durationMs, thumb)

    private fun msg(id: String, vararg atts: Attachment, deleted: Boolean = false, author: Peer = fred, text: String? = null) =
        ChatMessage(id, "c1", author, 1, at, deleted = deleted, text = text, attachments = if (deleted) emptyList() else atts.toList())

    private fun media(vararg ids: String = arrayOf("v1")) = VideoMedia(mapOf("t1" to thumbFile), ids.associateWith { clipFile })

    private fun ui(
        messages: List<ChatMessage>, media: ChatMediaSource, video: VideoSupport, selected: String? = "c1", showing: Boolean = true,
        overlayOpen: Boolean = false,
    ) = ChatsUi(
        listOf(
            ChatSummary("c1", "s1", "Friends", "Holiday planning", "me", rev = 5, readRev = 0, member = true, lastMessage = null),
            ChatSummary("c2", "s1", "Friends", "Game night", "me", rev = 5, readRev = 0, member = true, lastMessage = null),
        ),
        emptyList(), emptyMap(), "me", emptySet(), selected, if (selected == "c1") messages else emptyList(), listOf(me, fred), { it.name },
        notice = null, media = media, video = video, animate = showing, overlayOpen = overlayOpen,
    )

    private fun ComposeUiTest.show(ui: ChatsUi) = setContent { MjolnirTheme { ChatsTabContent(ui, ChatsActions(), now = { at }) } }

    private fun ComposeUiTest.awaitTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** Clicks the bubble's play button and waits for the inline player. */
    private fun ComposeUiTest.play(name: String = "clip.mp4") {
        onNodeWithContentDescription("Video $name").performClick()
        awaitTag("video-picture")
    }

    @Test fun thumbnailAndDurationBadge() = runComposeUiTest {
        val plain = video("v2", w = null, h = null, durationMs = null, thumb = null, name = "old.mov")
        show(ui(listOf(msg("m1", video()), msg("m2", plain)), media("v1", "v2"), FakeVideo()))
        awaitTag("video-thumb")
        onNodeWithText("0:42").assertExists() // the duration badge
        onNodeWithContentDescription("Video clip.mp4").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(180.dp)
        assertEquals(2, onAllNodes(hasContentDescription("Play"), useUnmergedTree = true).fetchSemanticsNodes().size)
        // No thumbnail or length (an old message, or capture failed): a plain tile with no badge.
        onNodeWithContentDescription("Video old.mov").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(180.dp)
        assertEquals(1, onAllNodes(hasTestTag("video-thumb"), useUnmergedTree = true).fetchSemanticsNodes().size)
        onNodeWithText("Video · ", substring = true).assertDoesNotExist() // never a file bubble any more
    }

    @Test fun downloadsThenPlays() = runComposeUiTest {
        val media = media().apply { gate = CompletableDeferred(); progress = 50L to 100L }
        val video = FakeVideo()
        show(ui(listOf(msg("m1", video())), media, video))
        onNodeWithContentDescription("Video clip.mp4").performClick()
        // Not cached yet: a ring on the play button shows the download.
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasContentDescription("Downloading video") and hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("video-picture").assertDoesNotExist()
        assertTrue("v1" in media.pins(), "pinned while it downloads and plays")
        media.gate.complete(Unit)
        awaitTag("video-picture")
        val engine = video.engines.single()
        assertEquals(clipFile, engine.loaded)
        assertTrue(engine.playing.value)
        engine.positionMs.value = 16_000
        awaitText("0:16 / 0:42")
        onNodeWithContentDescription("Pause").performClick()
        waitForIdle()
        assertFalse(engine.playing.value)
        onNodeWithContentDescription("Play").performClick()
        waitForIdle()
        assertTrue(engine.playing.value)
    }

    @Test fun unavailableOffersRetry() = runComposeUiTest {
        val media = media().apply { gate = CompletableDeferred<Unit>().apply { completeExceptionally(java.io.IOException("offline")) } }
        val video = FakeVideo()
        show(ui(listOf(msg("m1", video())), media, video))
        onNodeWithContentDescription("Video clip.mp4").performClick()
        awaitText("Video unavailable")
        onNodeWithText("Retry").assertExists() // the tile says a click tries again
        media.gate = CompletableDeferred(Unit) // back online
        onNodeWithText("Retry").performClick()
        awaitTag("video-picture")
    }

    @Test fun portraitBubbleKeepsAspect() = runComposeUiTest {
        val video = FakeVideo()
        show(ui(listOf(msg("m1", video(w = 1080, h = 1920))), media(), video))
        awaitTag("video-thumb")
        // 9:16 at most 400 tall: 225 × 400, not stretched to 320 wide.
        onNodeWithContentDescription("Video clip.mp4").assertWidthIsEqualTo(225.dp).assertHeightIsEqualTo(400.dp)
        play()
        onNodeWithTag("video-picture").assertWidthIsEqualTo(225.dp).assertHeightIsEqualTo(400.dp)
    }

    @Test fun unsupportedCodecShowsFallback() = runComposeUiTest {
        val unsupported = MediaException::class.java
            .getDeclaredConstructor(MediaException.Type::class.java, String::class.java)
            .apply { isAccessible = true }
            .newInstance(MediaException.Type.MEDIA_UNSUPPORTED, "Unrecognized file signature!")
        val video = FakeVideo { FakeEngine(failLoad = unsupported) }
        show(ui(listOf(msg("m1", video())), media(), video))
        onNodeWithContentDescription("Video clip.mp4").performClick()
        awaitText("Can't play this video here")
        onNodeWithTag("video-picture").assertDoesNotExist()
        assertTrue(video.engines.single().released)
        onNodeWithText("Open in player").performClick()
        assertEquals(listOf("v1" to clipFile), video.opened)
    }

    @Test fun hostileNameGetsNoOpenInPlayer() = runComposeUiTest {
        // Ruling CV-R4: a "video" named evil.exe that JavaFX can't play is never offered to the system.
        val video = FakeVideo { FakeEngine(failLoad = UnsupportedOperationException("not a video")) }
        show(ui(listOf(msg("m1", video(name = "evil.exe"))), media(), video))
        onNodeWithContentDescription("Video evil.exe").performClick()
        awaitText("Can't play this video here")
        onNodeWithText("Open in player").assertDoesNotExist()
        assertTrue(video.opened.isEmpty())
    }

    @Test fun playbackErrorShowsFallback() = runComposeUiTest {
        val video = FakeVideo()
        show(ui(listOf(msg("m1", video())), media(), video))
        play()
        val engine = video.engines.single()
        engine.error.value = "MEDIA_UNSUPPORTED" // reported by the player once it tried to decode
        awaitText("Can't play this video here")
        assertTrue(engine.released)
        onNodeWithText("Open in player").assertExists()
    }

    @Test fun leavingChatStopsPlayback() = runComposeUiTest {
        val media = media()
        val video = FakeVideo()
        var state by mutableStateOf(ui(listOf(msg("m1", video())), media, video))
        setContent { MjolnirTheme { ChatsTabContent(state, ChatsActions(), now = { at }) } }
        play()
        val engine = video.engines.single()
        // The window hidden: paused, still there.
        state = ui(listOf(msg("m1", video())), media, video, showing = false)
        waitForIdle()
        assertFalse(engine.playing.value); assertFalse(engine.released)
        // Another chat opened: released, and no longer pinned in the cache.
        state = ui(listOf(msg("m1", video())), media, video, selected = "c2")
        waitForIdle()
        assertTrue(engine.released)
        assertFalse("v1" in media.pins())
        assertTrue(media.pins().isEmpty(), media.pins().toString())
    }

    @Test fun unpinsOnlyOnceThePlayerHasLetGo() = runComposeUiTest {
        // Windows can't delete a file JavaFX still holds: the pin goes only after the player is disposed (finding I1).
        val media = media()
        val video = FakeVideo { FakeEngine(holdRelease = true) }
        var state by mutableStateOf(ui(listOf(msg("m1", video())), media, video))
        setContent { MjolnirTheme { ChatsTabContent(state, ChatsActions(), now = { at }) } }
        play()
        val engine = video.engines.single()
        state = ui(listOf(msg("m1", video())), media, video, selected = "c2")
        waitForIdle()
        assertTrue(engine.released)
        assertEquals(listOf("v1"), media.pins(), "still pinned while the player lets go of the file")
        engine.finishRelease()
        assertTrue(media.pins().isEmpty(), media.pins().toString())
    }

    @Test fun deletedDuringPlaybackStops() = runComposeUiTest {
        val media = media()
        val video = FakeVideo()
        var state by mutableStateOf(ui(listOf(msg("m1", video())), media, video))
        setContent { MjolnirTheme { ChatsTabContent(state, ChatsActions(), now = { at }) } }
        play()
        val engine = video.engines.single()
        state = ui(listOf(msg("m1", deleted = true)), media, video)
        awaitText("Message deleted")
        assertTrue(engine.released)
        assertFalse(engine.playing.value)
        onNodeWithTag("video-picture").assertDoesNotExist()
    }

    @Test fun onlyOneVideoPlays() = runComposeUiTest {
        val video = FakeVideo()
        show(ui(listOf(msg("m1", video()), msg("m2", video("v2", name = "other.mp4"))), media("v1", "v2"), video))
        play("clip.mp4")
        val first = video.engines.single()
        assertTrue(first.playing.value)
        onNodeWithContentDescription("Video other.mp4").performClick()
        waitUntil(timeoutMillis = 5_000) { video.engines.size == 2 && video.engines[1].playing.value }
        assertFalse(first.playing.value, "starting the second paused the first")
        // Resuming the first pauses the second again.
        onNodeWithContentDescription("Play").performClick()
        waitForIdle()
        assertTrue(first.playing.value)
        assertFalse(video.engines[1].playing.value)
        assertNull(first.error.value)
        assertSame(first, video.engines.first())
    }

    @Test fun noJavaFxShowsFallbackAndNeverThrows() = runComposeUiTest {
        // JavaFX unavailable: load throws, and so does release (ruling on C1); neither may escape.
        val video = FakeVideo { FakeEngine(failLoad = UnsupportedOperationException("JavaFX can't start here"), failRelease = IllegalStateException("not started")) }
        val media = media()
        var state by mutableStateOf(ui(listOf(msg("m1", video())), media, video))
        setContent { MjolnirTheme { ChatsTabContent(state, ChatsActions(), now = { at }) } }
        onNodeWithContentDescription("Video clip.mp4").performClick()
        awaitText("Can't play this video here")
        assertTrue(video.engines.single().released)
        state = ui(listOf(msg("m1", video())), media, video, selected = "c2") // the bubble leaves composition
        waitForIdle()
        onNodeWithText("Can't play this video here").assertDoesNotExist()
        assertTrue(media.pins().isEmpty(), media.pins().toString())
    }

    @Test fun overlaysPauseAndHideThePlayer() = runComposeUiTest {
        val photo = Attachment("p1", AttachmentKind.IMAGE, "lake.jpg", 100, "image/jpeg", 320, 200, thumbFileId = "t1")
        val media = media()
        val video = FakeVideo()
        var state by mutableStateOf(ui(listOf(msg("m0", photo), msg("m1", video(), author = me)), media, video))
        setContent { MjolnirTheme { ChatsTabContent(state, ChatsActions(), now = { at }) } }
        play()
        val engine = video.engines.single()
        // The image viewer over the conversation.
        onNodeWithContentDescription("Photo lake.jpg").performClick()
        awaitTag("video-cover")
        assertFalse(engine.playing.value)
        onNodeWithTag("video-picture").assertDoesNotExist()
        onNodeWithText("Close").performClick()
        awaitTag("video-picture") // shown again, still paused until Play
        assertFalse(engine.playing.value)
        // Message Info.
        onNodeWithContentDescription("Play").performClick(); waitForIdle()
        assertTrue(engine.playing.value)
        onAllNodes(hasContentDescription("Message menu"))[1].performClick()
        onNodeWithText("Info").performClick()
        awaitTag("video-cover")
        assertFalse(engine.playing.value)
        onNodeWithTag("video-picture").assertDoesNotExist()
        onNodeWithText("Close").performClick(); awaitTag("video-picture")
        // A dialog of the tab (New chat, Invite, Settings).
        engine.play()
        state = ui(listOf(msg("m0", photo), msg("m1", video(), author = me)), media, video, overlayOpen = true)
        awaitTag("video-cover")
        assertFalse(engine.playing.value)
        assertFalse(engine.released)
    }

    @Test fun partlyOutOfTheListPauses() = runDesktopComposeUiTest(1040, 760) {
        val media = media()
        val video = FakeVideo()
        val texts = (1..6).map { msg("t$it", text = "line $it") }
        show(ui(texts + msg("m1", video(w = 1080, h = 1920)), media, video))
        play() // the newest message is scrolled to: wholly in view
        val engine = video.engines.single()
        assertTrue(engine.playing.value)
        // Scrolled up a little: the bubble's bottom slides under the composer.
        onAllNodes(hasScrollAction())[1].performMouseInput { scroll(-2f) }
        awaitTag("video-cover")
        assertFalse(engine.playing.value)
        assertFalse(engine.released) // still on screen, so kept
        onNodeWithTag("video-picture").assertDoesNotExist()
    }
}
