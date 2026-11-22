@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.mjolnir.client.PendingFile
import app.mjolnir.client.PendingSend
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatBubblesTest {
    private val me = Peer("me", "This PC", DeviceKind.PC)
    private val fred = Peer("fred", "Fred's phone", DeviceKind.PHONE)
    private val at = 1_700_000_000_000L
    private val dir: File = Files.createTempDirectory("bubbles").toFile().apply { deleteOnExit() }

    private fun png(name: String, w: Int, h: Int, color: Color): File = File(dir, name).also { f ->
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { this.color = color; fillRect(0, 0, w, h); dispose() }
        ImageIO.write(img, "png", f)
    }

    private fun att(
        id: String, kind: AttachmentKind, name: String, size: Long, thumb: String? = null, w: Int? = null, h: Int? = null, path: String? = null,
    ) = Attachment(id, kind, name, size, "application/octet-stream", w, h, thumbFileId = thumb, relativePath = path)

    private fun msg(id: String, author: Peer, text: String?, attachments: List<Attachment>) =
        ChatMessage(id, "c1", author, 1, at, text = text, attachments = attachments)

    private fun ui(messages: List<ChatMessage> = emptyList(), pending: List<PendingSend> = emptyList(), media: ChatMediaSource = NoChatMedia) = ChatsUi(
        listOf(ChatSummary("c1", "s1", "Friends", "Holiday planning", "me", rev = 5, readRev = 0, member = true, lastMessage = null)),
        emptyList(), emptyMap(), "me", emptySet(), "c1", messages, listOf(me, fred), { it.name }, notice = null,
        pending = pending, media = media,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(ui: ChatsUi, actions: ChatsActions = ChatsActions()) =
        setContent { MjolnirTheme { ChatsTabContent(ui, actions, now = { at }) } }

    /** Thumbnails come at once; full files wait for [full] to complete. Records pins. */
    private class FakeMedia(val thumbs: Map<String, File>, val full: CompletableDeferred<File> = CompletableDeferred()) : ChatMediaSource {
        val pinned = mutableListOf<String>()
        override suspend fun thumbnail(att: Attachment): File? = att.thumbFileId?.let(thumbs::get)
        override suspend fun file(att: Attachment): File = full.await()
        override fun pin(fileId: String) { synchronized(pinned) { pinned += fileId } }
        override fun unpin(fileId: String) { synchronized(pinned) { pinned -= fileId } }
    }

    @Test fun imageBubbleUsesThumbThenFull() = runComposeUiTest {
        val media = FakeMedia(mapOf("t1" to png("thumb.png", 32, 20, Color.RED)))
        show(ui(listOf(msg("m1", fred, null, listOf(att("f1", AttachmentKind.IMAGE, "IMG_2041.jpg", 2_000_000, thumb = "t1", w = 1600, h = 1000)))), media = media))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTagOf("image-thumb"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("image-full", useUnmergedTree = true).assertDoesNotExist()
        media.full.complete(png("full.png", 640, 400, Color.BLUE))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTagOf("image-full"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("image-thumb", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(setOf("f1", "t1"), synchronized(media.pinned) { media.pinned.toSet() }) // kept in the cache while shown
    }

    @Test fun clickingAnImageOpensTheViewer() = runComposeUiTest {
        val media = FakeMedia(mapOf("t1" to png("thumb2.png", 32, 20, Color.RED)), CompletableDeferred(png("full2.png", 64, 40, Color.BLUE)))
        show(ui(listOf(msg("m1", fred, null, listOf(att("f1", AttachmentKind.IMAGE, "IMG_2041.jpg", 2_000_000, thumb = "t1", w = 64, h = 40)))), media = media))
        onNodeWithContentDescription("Photo IMG_2041.jpg").performClick()
        onNodeWithContentDescription("Image viewer").assertExists()
        onNodeWithText("Close").performClick()
        onNodeWithContentDescription("Image viewer").assertDoesNotExist()
    }

    @Test fun gifShowsBadge() = runComposeUiTest {
        show(ui(listOf(msg("m1", me, null, listOf(att("g1", AttachmentKind.GIF, "dance.gif", 300_000, w = 180, h = 120))))))
        onNodeWithText("GIF").assertExists()
    }

    @Test fun fileBubbleShowsNameAndSize() = runComposeUiTest {
        val downloads = mutableListOf<List<String>>()
        val pdf = att("f1", AttachmentKind.FILE, "itinerary.pdf", 225_280)
        show(ui(listOf(msg("m1", fred, null, listOf(pdf)))), ChatsActions(onDownload = { _, a -> downloads += a.map { it.fileId } }))
        // Ruling CM-R4: the name, then "{label} · {size}".
        onNodeWithText("itinerary.pdf").assertExists()
        onNodeWithText("File · 220 KB").assertExists()
        onNodeWithText("PDF").assertExists() // the type tag
        onNodeWithContentDescription("Download itinerary.pdf").performClick()
        assertEquals(listOf(listOf("f1")), downloads)
    }

    @Test fun folderBubbleCounts() = runComposeUiTest {
        val files = listOf(
            att("a", AttachmentKind.IMAGE, "a.jpg", 1L shl 20, path = "Holiday 2026/a.jpg"),
            att("b", AttachmentKind.FILE, "b.pdf", 1L shl 20, path = "Holiday 2026/docs/b.pdf"),
            att("c", AttachmentKind.FILE, "c.txt", 1L shl 20, path = "Holiday 2026/c.txt"),
        )
        val downloads = mutableListOf<List<String>>()
        show(ui(listOf(msg("m1", fred, null, files))), ChatsActions(onDownload = { _, a -> downloads += a.map { it.fileId } }))
        onNodeWithText("Holiday 2026").assertExists()
        onNodeWithText("Folder · 3 files · 3 MB").assertExists()
        onNodeWithText("a.jpg", substring = true).assertDoesNotExist() // one bubble for the folder, not one per file
        onNodeWithContentDescription("Download Holiday 2026").performClick()
        assertEquals(listOf(listOf("a", "b", "c")), downloads)
    }

    @Test fun menuHasDownloadForMedia() = runComposeUiTest {
        val downloads = mutableListOf<Pair<String, List<String>>>()
        val m = msg("m1", fred, "the plan", listOf(att("f1", AttachmentKind.FILE, "itinerary.pdf", 225_280), att("f2", AttachmentKind.FILE, "map.pdf", 1_000)))
        show(ui(listOf(m)), ChatsActions(onDownload = { msg, a -> downloads += msg.id to a.map { it.fileId } }))
        onNodeWithContentDescription("Message menu").performClick() // someone else's message without a link has a menu only for its media
        onNodeWithText("Edit").assertDoesNotExist()
        onNodeWithText("Download").performClick()
        assertEquals(listOf("m1" to listOf("f1", "f2")), downloads)
    }

    @Test fun deletedMediaMessageHasNoDownload() = runComposeUiTest {
        val m = ChatMessage("m1", "c1", fred, 2, at, deleted = true, attachments = emptyList())
        show(ui(listOf(m)))
        onNodeWithContentDescription("Message menu").assertDoesNotExist()
    }

    @Test fun pendingSendGreyedWithProgress() = runComposeUiTest {
        val dismissed = mutableListOf<String>()
        val photo = png("local.png", 40, 30, Color.GREEN)
        val sending = PendingSend(
            "p1", "c1", "look", listOf(
                PendingFile("local.png", AttachmentKind.IMAGE, 600, null, photo.path, null),
                PendingFile("notes.txt", AttachmentKind.FILE, 400, null, "/nowhere/notes.txt", null),
            ),
            sentBytes = 400, totalBytes = 1_000,
        )
        val failed = PendingSend(
            "p2", "c1", null, listOf(PendingFile("big.zip", AttachmentKind.FILE, 9_000, null, "/nowhere/big.zip", null)),
            sentBytes = 0, totalBytes = 9_000, failure = "Not sent: a file is too big for the relay.",
        )
        val other = PendingSend("p3", "c2", "elsewhere", emptyList(), 0, 0)
        show(ui(pending = listOf(sending, failed, other)), ChatsActions(onDismissSend = { dismissed += it }))
        onNodeWithText("look").assertExists()
        onNodeWithText("notes.txt").assertExists()
        onNodeWithText("File · 400 B").assertExists()
        onNodeWithText("400 B of 1000 B · 40%").assertExists()
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.4f, 0f..1f))).assertExists()
        onNodeWithContentDescription("Sending").assertExists() // the greyed group
        onNodeWithText("elsewhere").assertDoesNotExist() // another chat's send
        onNodeWithText("Not sent: a file is too big for the relay.").assertExists()
        onNodeWithText("big.zip").assertExists()
        onNodeWithText("File · 9 KB").assertExists()
        onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("p2"), dismissed)
        assertTrue(onAllNodes(hasTestTagOf("pending-p1")).fetchSemanticsNodes().isNotEmpty())
        // A send still going can be stopped.
        onNodeWithText("Cancel").performClick()
        assertEquals(listOf("p2", "p1"), dismissed)
        // Its text comes after its files, as in the posted message.
        val textTop = onNodeWithText("look").fetchSemanticsNode().boundsInRoot.top
        assertTrue(textTop > onNodeWithText("notes.txt").fetchSemanticsNode().boundsInRoot.top, "text below the files")
    }

    @Test fun ownMediaOnlyMessageHasInfoButNoEdit() = runComposeUiTest {
        val overlay = mutableListOf<Boolean>()
        show(ui(listOf(msg("m1", me, null, listOf(att("f1", AttachmentKind.FILE, "report.pdf", 1_000))))), ChatsActions(onOverlayChange = { overlay += it }))
        onNodeWithContentDescription("Message menu").performClick()
        onNodeWithText("Edit").assertDoesNotExist()
        onNodeWithText("Info").performClick()
        onNodeWithContentDescription("Message info").assertExists()
        assertEquals(true, overlay.last()) // Ctrl+V sends nothing while it is open
        onNodeWithText("Close").performClick()
        waitForIdle()
        assertEquals(false, overlay.last())
    }

    @Test fun mediaThatFailedOfflineLoadsOnReconnect() = runComposeUiTest {
        val thumb = png("thumb2.png", 32, 20, Color.RED)
        val media = object : ChatMediaSource {
            @Volatile var offline = true
            override val connects = kotlinx.coroutines.flow.MutableStateFlow(0)
            override suspend fun thumbnail(att: Attachment): File? = if (offline) throw java.io.IOException("offline") else thumb
            override suspend fun file(att: Attachment): File = throw java.io.IOException("not needed")
        }
        show(ui(listOf(msg("m1", fred, null, listOf(att("f1", AttachmentKind.IMAGE, "IMG.jpg", 20_000_000, thumb = "t1", w = 1600, h = 1000)))), media = media))
        waitForIdle()
        onNodeWithTag("image-thumb", useUnmergedTree = true).assertDoesNotExist()
        media.offline = false
        media.connects.value = 1 // the relay is back
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTagOf("image-thumb"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun oversizedGifKeepsItsStill() = runComposeUiTest {
        // A valid GIF whose header claims a 65535 × 65535 canvas: never animated (no multi-GB frame), the still stays.
        val huge = File(dir, "huge.gif").apply { writeBytes(hugeCanvasGif()) }
        val media = object : ChatMediaSource {
            @Volatile var fetched = false
            override suspend fun thumbnail(att: Attachment): File? = png("still.png", 18, 12, Color.ORANGE)
            override suspend fun file(att: Attachment): File = huge.also { fetched = true }
        }
        show(ui(listOf(msg("m1", fred, null, listOf(att("g1", AttachmentKind.GIF, "huge.gif", huge.length(), thumb = "t1", w = 180, h = 120)))), media = media))
        waitUntil(timeoutMillis = 5_000) { media.fetched && onAllNodes(hasTestTagOf("gif-still"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(300); waitForIdle() // time for the decode it refuses
        onNodeWithTag("gif-animated", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("gif-still", useUnmergedTree = true).assertExists()
    }

    @Test fun viewerDecodeIsNotCached() = runComposeUiTest {
        val full = png("viewer-full.png", 900, 600, Color.MAGENTA)
        val media = FakeMedia(emptyMap(), CompletableDeferred(full))
        setContent { MjolnirTheme { ImageViewer(att("f9", AttachmentKind.IMAGE, "big.png", full.length(), w = 900, h = 600), media, true, {}, {}) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTagOf("viewer-image"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(thumbnailCached(full.path)) // one big picture is not kept in the shared thumbnail cache
        assertEquals(setOf("f9"), synchronized(media.pinned) { media.pinned.toSet() })
    }

    private fun hasTestTagOf(tag: String) = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, tag)
}
