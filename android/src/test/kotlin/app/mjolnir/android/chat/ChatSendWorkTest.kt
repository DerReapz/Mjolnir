package app.mjolnir.android.chat

import app.mjolnir.android.receive.syncChats
import app.mjolnir.client.ChatController
import app.mjolnir.client.ChatMediaCache
import app.mjolnir.client.ChatOutbox
import app.mjolnir.client.ChatStore
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.PendingSend
import app.mjolnir.client.RelayApi
import app.mjolnir.client.Thumbnailer
import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.UploadStatus
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendWorkTest {
    @get:Rule val tmp = TemporaryFolder()

    /** [fake], except that every upload chunk waits for [gate] (a long upload in progress). */
    private class SlowUploads(val fake: FakeRelayApi, val gate: CompletableDeferred<Unit> = CompletableDeferred()) : RelayApi by fake {
        var started = CompletableDeferred<Unit>()
        override suspend fun uploadChunk(uploadId: String, start: Long, total: Long, bytes: ByteArray, length: Int): UploadStatus {
            started.complete(Unit)
            gate.await()
            return fake.uploadChunk(uploadId, start, total, bytes, length)
        }
    }

    private fun pending(id: String, failure: String? = null) = PendingSend(id, "c", null, emptyList(), 0, 1, failure)

    @Test fun inboxSyncDoesNotWaitForSends() = runTest {
        val fake = FakeRelayApi()
        fake.asMemberOf("s1", Peer("admin", "Admin", DeviceKind.PC), name = "Crew")
        val chat = fake.addChat("s1", "Trip", owner = fake.me)
        val api = SlowUploads(fake)
        val controller = ChatController(
            { api }, ChatStore(tmp.newFolder("chats")), TestScope(UnconfinedTestDispatcher(testScheduler)).backgroundScope,
            outbox = ChatOutbox(tmp.newFolder("outbox")), media = ChatMediaCache(tmp.newFolder("media")),
        ) { fake.me.deviceId }
        val file = File(tmp.newFolder(), "big.bin").apply { writeBytes(ByteArray(64)) }
        controller.send(chat, null, listOf(file), Thumbnailer { _, _ -> null })
        api.started.await() // the upload is running and holds the send lock
        var scheduled = 0
        // Would time out if the doorbell waited on the upload; it only hands the sends to their own work.
        withTimeout(5_000) { syncChats(controller) { scheduled++ } }
        assertEquals(1, scheduled)
        assertEquals(listOf("Trip"), controller.chats.value.map { it.name }) // the chats were still refreshed
        assertTrue(controller.pendingSends.value.single().sentBytes < 64)
        api.gate.complete(Unit) // let the upload finish
        controller.pendingSends.first { it.isEmpty() }
        syncChats(controller) { scheduled++ }
        assertEquals(1, scheduled) // nothing waiting: no work
    }

    @Test fun sendWorkIsEnqueuedWhenSendsStartWaiting() = runTest {
        val pendingSends = MutableStateFlow(listOf(pending("a")))
        var enqueued = 0
        scheduleChatSendsWhenWaiting(pendingSends, backgroundScope) { enqueued++ }
        runCurrent()
        assertEquals(1, enqueued) // left over at start
        pendingSends.value = listOf(pending("a"), pending("b")) // progress, another send: still the one work
        runCurrent(); assertEquals(1, enqueued)
        pendingSends.value = listOf(pending("a", failure = "Not sent: …")) // only a failed one: waits for Dismiss
        runCurrent(); assertEquals(1, enqueued)
        pendingSends.value = listOf(pending("a", failure = "Not sent: …"), pending("c")) // a new send queued
        runCurrent(); assertEquals(2, enqueued)
        assertTrue(sendsWaiting(listOf(pending("x"))))
        assertTrue(!sendsWaiting(listOf(pending("x", failure = "no"))))
    }

    @Test fun sendingNotificationSumsTheSendsStillGoing() {
        val trip = PendingSend("a", "c1", null, emptyList(), 30, 100)
        val crew = PendingSend("b", "c2", null, emptyList(), 50, 100)
        val failed = PendingSend("x", "c3", null, emptyList(), 0, 900, failure = "Not sent")
        assertEquals(30L to 100L, chatSendTotals(listOf(trip, failed)))
        assertEquals(80L to 200L, chatSendTotals(listOf(trip, crew, failed)))
        val names = mapOf("c1" to "Trip", "c2" to "Crew")
        assertEquals("Sending to Trip", chatSendTitle(listOf(trip, failed), names::get))
        assertEquals("Sending to 2 chats", chatSendTitle(listOf(trip, crew), names::get))
        assertEquals("Sending to a chat", chatSendTitle(listOf(trip.copy(chatId = "gone")), names::get))
    }

    @Test fun viewerGifOverTheCapKeepsItsStill() {
        val stills = mutableListOf<Long>()
        val still = { cap: Long -> stills += cap; "still" }
        // A GIF that won't animate and is over the byte cap: never decoded whole, the thumbnail stays.
        assertEquals(ViewerPicture<String, String>(), viewerPicture<String, String>(AttachmentKind.GIF, 40L shl 20, 32L shl 20, animate = { null }, still = still))
        assertTrue(stills.isEmpty())
        // Under the byte cap (e.g. a canvas over the animation cap): a still only within the GIF pixel cap.
        assertEquals("still", viewerPicture<String, String>(AttachmentKind.GIF, 1L shl 20, 32L shl 20, animate = { null }, still = still).still)
        assertEquals(listOf(GIF_MAX_PIXELS), stills)
        // Animating: no still decode at all.
        val animated = viewerPicture<String, String>(AttachmentKind.GIF, 1L shl 20, 32L shl 20, animate = { "anim" }, still = still)
        assertEquals("anim", animated.animated); assertNull(animated.still); assertEquals(1, stills.size)
        // A photo gets the still cap.
        viewerPicture<String, String>(AttachmentKind.IMAGE, 1L shl 20, 32L shl 20, animate = { error("photos don't animate") }, still = still)
        assertEquals(listOf(GIF_MAX_PIXELS, STILL_MAX_PIXELS), stills)
    }
}
