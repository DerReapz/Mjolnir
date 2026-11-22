package app.mjolnir.android.ui

import androidx.lifecycle.SavedStateHandle
import app.mjolnir.android.MainDispatcherRule
import app.mjolnir.android.chat.ChatFileImporter
import app.mjolnir.android.chat.ChatImport
import app.mjolnir.android.chat.ChatMediaSupport
import app.mjolnir.android.chat.ChatSendFiles
import app.mjolnir.android.chat.fitWithin
import app.mjolnir.android.data.SavedFile
import app.mjolnir.android.receive.FilePublisher
import app.mjolnir.client.ChatController
import app.mjolnir.client.ChatMediaCache
import app.mjolnir.client.ChatOutbox
import app.mjolnir.client.ChatStore
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Messages
import app.mjolnir.client.Nicknames
import app.mjolnir.client.ReceivedPath
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.client.Thumbnailer
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.UploadStatus
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakeRelayApi()
    private val sam = Peer("sam", "Sam", DeviceKind.PHONE)
    private val admin = Peer("admin", "Admin", DeviceKind.PC)
    private val me get() = fake.me

    /** What the fake MediaStore received: the folder, the name and the bytes of each file. */
    private class FakePublisher(val folders: MutableSet<String> = mutableSetOf()) : FilePublisher {
        val saved = mutableListOf<Triple<String?, String, List<Byte>>>()
        override fun publish(part: File, target: ReceivedPath, ref: FileRef): SavedFile {
            saved += Triple(target.folder, target.fileName, part.readBytes().toList())
            target.folder?.let { folders += it.substringBefore('/') }
            return SavedFile(target.fileName, "content://media/${saved.size}", part.length(), ref.mimeType)
        }
        override fun folderExists(name: String): Boolean = name in folders
    }

    /** [fake], except that upload chunk number [failChunk] (1-based, counting all) fails with a network error. */
    private class FlakyUploads(val fake: FakeRelayApi, var failChunk: Int? = null) : RelayApi by fake {
        var chunks = 0
        override suspend fun uploadChunk(uploadId: String, start: Long, total: Long, bytes: ByteArray, length: Int): UploadStatus {
            if (++chunks == failChunk) throw RelayException(RelayErrorKind.NETWORK, "down")
            return fake.uploadChunk(uploadId, start, total, bytes, length)
        }
    }

    private val publisher = FakePublisher()
    private val picked = mutableMapOf<String, File>()
    private lateinit var sendFiles: ChatSendFiles
    private lateinit var exportDir: File
    private lateinit var nicknames: Nicknames
    private lateinit var cache: ChatMediaCache

    @Before fun setUp() {
        nicknames = Nicknames(JsonFileStore(File(tmp.root, "nicknames.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() })
        sendFiles = ChatSendFiles(File(tmp.root, "chat-send"))
        exportDir = File(tmp.root, "chat-export")
        cache = ChatMediaCache(File(tmp.root, "chat-media"))
        fake.asMemberOf("s1", admin, name = "Crew")
        fake.addMember("s1", sam)
    }

    private fun controller(api: RelayApi = fake) = ChatController(
        { api }, ChatStore(File(tmp.root, "chats").apply { mkdirs() }), CoroutineScope(main.dispatcher),
        outbox = ChatOutbox(File(tmp.root, "chat-outbox"), chunkBytes = 4), media = cache,
    ) { me.deviceId }

    /** Copies what was "picked" (uri → file) into the send's folder under its own name, as the phone's importer does. */
    private val importer = ChatFileImporter { uris, dir ->
        ChatImport(uris.map { u -> picked.getValue(u).copyTo(File(File(dir, u.hashCode().toString()), picked.getValue(u).name)) }, emptyList())
    }

    private fun vm(c: ChatController) = ChatViewModel(
        c, fake, nicknames, { me.deviceId }, MutableStateFlow(true), MutableStateFlow(true), SavedStateHandle(),
        media = ChatMediaSupport(cache, Thumbnailer { _, _ -> null }, publisher, sendFiles, importer, exportDir),
        io = main.dispatcher,
    )

    private fun pick(uri: String, name: String, size: Int): String {
        picked[uri] = File(tmp.newFolder(), name).apply { writeBytes(ByteArray(size) { (it * 3 + 1).toByte() }) }
        return uri
    }

    private fun att(id: String, kind: AttachmentKind, name: String, size: Long, path: String? = null) =
        Attachment(id, kind, name, size, "application/octet-stream", relativePath = path)

    private fun msg(author: Peer, text: String?, attachments: List<Attachment>, deleted: Boolean = false) =
        ChatMessage("m", "c", author, 1, 1, text = text, deleted = deleted, attachments = attachments)

    @Test fun sheetActionsForMedia() {
        val photo = listOf(att("f", AttachmentKind.IMAGE, "a.jpg", 10))
        assertEquals(listOf(MessageAction.DOWNLOAD, MessageAction.SHARE, MessageAction.INFO, MessageAction.EDIT, MessageAction.DELETE),
            messageActions(msg(me, "look", photo), me.deviceId, isSpaceAdmin = false))
        // A media-only message of ours has no text to edit.
        assertEquals(listOf(MessageAction.DOWNLOAD, MessageAction.SHARE, MessageAction.INFO, MessageAction.DELETE),
            messageActions(msg(me, null, photo), me.deviceId, isSpaceAdmin = false))
        assertEquals(listOf(MessageAction.DOWNLOAD, MessageAction.SHARE), messageActions(msg(sam, null, photo), me.deviceId, isSpaceAdmin = false))
        assertEquals(listOf(MessageAction.DOWNLOAD, MessageAction.SHARE, MessageAction.DELETE), messageActions(msg(sam, null, photo), me.deviceId, isSpaceAdmin = true))
        assertEquals(listOf(MessageAction.SHARE), messageActions(msg(sam, "hi", emptyList()), me.deviceId, isSpaceAdmin = false))
        assertTrue(messageActions(msg(sam, null, photo, deleted = true), me.deviceId, isSpaceAdmin = false).isEmpty())
        // The sheet's first line, as in the mockup ("Video · 18 MB · from Fred's phone").
        assertEquals("Video · 18 MB", sheetSummary(msg(sam, null, listOf(att("v", AttachmentKind.VIDEO, "trip.mp4", 18L shl 20)))))
        assertEquals("look", sheetSummary(msg(sam, "look", photo)))
        assertEquals("2 files · 20 B", sheetSummary(msg(sam, null, photo + att("g", AttachmentKind.FILE, "b.txt", 10))))
        // Photos decode straight to the bubble's size from their real size, never enlarged.
        assertEquals(320 to 240, fitWithin(12000, 9000, 320))
        assertEquals(40 to 30, fitWithin(40, 30, 320))
    }

    /** The info and the notice once either is set. */
    private suspend fun outcome(vm: ChatViewModel): Pair<String?, String?> =
        combine(vm.info, vm.notice) { i, n -> i to n }.first { it.first != null || it.second != null }

    @Test fun downloadPublishesToMediaStore() = runTest {
        val chat = fake.addChat("s1", "Trip", owner = me, members = listOf(me, sam))
        val doc = ByteArray(9) { it.toByte() }
        fake.putBlob("f-1", "doc.pdf", doc)
        fake.putBlob("f-2", "a.jpg", byteArrayOf(1, 2))
        fake.putBlob("f-3", "b.txt", byteArrayOf(3))
        val single = fake.incoming(chat, sam, null, attachments = listOf(att("f-1", AttachmentKind.FILE, "doc.pdf", 9)))
        val folder = fake.incoming(chat, sam, "pics", attachments = listOf(
            att("f-2", AttachmentKind.FILE, "a.jpg", 2, "Holiday/a.jpg"), att("f-3", AttachmentKind.FILE, "b.txt", 1, "Holiday/sub/b.txt"),
        ))
        fake.putBlob("f-4", "late.pdf", ByteArray(4))
        val late = fake.incoming(chat, sam, null, attachments = listOf(att("f-4", AttachmentKind.FILE, "late.pdf", 4)))
        publisher.folders += "Holiday" // already in Download/Mjolnir
        val c = controller()
        val vm = vm(c)
        vm.open(chat); c.sync(chat); advanceUntilIdle()

        vm.download(vm.messages.value.single { it.id == single.id })
        assertEquals("Saved to Downloads" to null, outcome(vm)) // the copy out of the cache runs on the IO threads
        assertEquals(Triple(null, "doc.pdf", doc.toList()), publisher.saved.single())
        assertNull(vm.notice.value)

        vm.dismissInfo()
        vm.download(vm.messages.value.single { it.id == folder.id })
        assertEquals("Saved to Downloads" to null, outcome(vm))
        assertEquals(listOf(Triple("Holiday (1)", "a.jpg", listOf<Byte>(1, 2)), Triple("Holiday (1)/sub", "b.txt", listOf<Byte>(3))), publisher.saved.drop(1))
        assertTrue(exportDir.walk().none { it.isFile }, "staging left ${exportDir.walk().toList()}")

        // Deleted meanwhile (its file not fetched yet): says so, and nothing reaches Downloads.
        vm.dismissInfo()
        val gone = vm.messages.value.single { it.id == late.id }
        fake.incomingDelete(chat, late.id)
        vm.download(gone)
        assertEquals(null to Messages.MESSAGE_DELETED, outcome(vm))
        assertNull(vm.info.value)
        assertEquals(3, publisher.saved.size)
        assertTrue(exportDir.walk().none { it.isFile })
    }

    @Test fun shareIntoChatSendsFiles() = runTest {
        val chat = fake.addChat("s1", "Trip", owner = me, members = listOf(me, sam))
        val c = controller()
        val vm = vm(c)
        val thrown = mutableListOf<String>()
        val uri = pick("content://picked/1", "photo.png", 10)
        vm.offerShare(SharedContent("look", listOf(uri), throwInstead = { thrown += "tray" })); advanceUntilIdle()
        val offer = assertNotNull(vm.shareOffer.value)
        assertEquals(listOf("Trip"), offer.chats.map { it.name })
        assertTrue(thrown.isEmpty())

        vm.sendShare(chat); advanceUntilIdle()
        assertNull(vm.shareOffer.value)
        assertEquals(chat, vm.selectedId.value) // the chat opens on the send
        val posted = c.messages(chat).first { list -> list.any { it.author.deviceId == me.deviceId } }.single { it.author.deviceId == me.deviceId }
        assertEquals("look", posted.text)
        assertEquals(listOf("photo.png"), posted.attachments.map { it.name })
        assertEquals(picked.getValue(uri).readBytes().toList(), fake.blobs.getValue(posted.attachments.single().fileId).toList())
        c.pendingSends.first { it.isEmpty() }
        sendFiles.prune(c.pendingSends.value) // the app prunes on every change: the copy is gone once posted
        assertTrue(File(tmp.root, "chat-send").listFiles().orEmpty().isEmpty())

        // With no chat to send to, a share goes to the Throw tray as before.
        val lonely = ChatViewModel(
            controller(FakeRelayApi()), FakeRelayApi(), nicknames, { "x" }, MutableStateFlow(true), MutableStateFlow(true), SavedStateHandle(), io = main.dispatcher,
        )
        lonely.offerShare(SharedContent(null, listOf(uri), throwInstead = { thrown += "tray" })); advanceUntilIdle()
        assertNull(lonely.shareOffer.value)
        assertEquals(listOf("tray"), thrown)
        // Choosing Throw from the picker does the same.
        vm.offerShare(SharedContent(null, listOf(uri), throwInstead = { thrown += "tray" })); advanceUntilIdle()
        vm.throwShare()
        assertNull(vm.shareOffer.value); assertEquals(listOf("tray", "tray"), thrown)
    }

    @Test fun pendingSendShowsProgress() = runTest {
        val chat = fake.addChat("s1", "Trip", owner = me, members = listOf(me, sam))
        val other = fake.addChat("s1", "Other", owner = me, members = listOf(me, sam))
        val flaky = FlakyUploads(fake, failChunk = 2)
        val c = controller(flaky)
        val vm = vm(c)
        c.refresh(); advanceUntilIdle()
        vm.open(chat)
        vm.attach(listOf(pick("content://picked/a", "a.bin", 10))); advanceUntilIdle()
        val pending = vm.pending.first { list -> list.isNotEmpty() && list.single().sentBytes > 0 }.single()
        assertEquals(chat, pending.chatId); assertNull(pending.failure)
        assertEquals(listOf("a.bin"), pending.files.map { it.name })
        assertEquals(4L to 10L, pending.sentBytes to pending.totalBytes) // the second chunk failed
        assertEquals("4 B of 10 B · 40%", sendProgress(pending.sentBytes, pending.totalBytes))
        vm.open(other)
        assertTrue(vm.pending.first { it.isEmpty() }.isEmpty()) // only the open chat's sends
        vm.open(chat)
        vm.pending.first { it.isNotEmpty() }

        // Back in the foreground (or the network returns): the send resumes and posts.
        c.resumeSends().join()
        vm.pending.first { it.isEmpty() }
        assertEquals(listOf("a.bin"), c.messages(chat).value.single { it.author.deviceId == me.deviceId }.attachments.map { it.name })

        // A send that fails for good shows why until dismissed.
        fake.failUploadsWith = RelayException(RelayErrorKind.STORAGE_FULL, "relay_full")
        vm.attach(listOf(pick("content://picked/b", "b.bin", 10))); advanceUntilIdle()
        val failed = vm.pending.first { list -> list.any { it.failure != null } }.single()
        assertEquals("Not sent: the relay is out of space.", failed.failure)
        assertEquals(Messages.DISMISS, pendingAction(failed))
        vm.dismissSend(failed.id)
        assertTrue(vm.pending.first { it.isEmpty() }.isEmpty())
    }

    @Test fun aSendStillGoingCanBeCancelled() = runTest {
        val chat = fake.addChat("s1", "Trip", owner = me, members = listOf(me, sam))
        val c = controller(FlakyUploads(fake, failChunk = 2))
        val vm = vm(c)
        c.refresh(); advanceUntilIdle()
        vm.open(chat)
        vm.attach(listOf(pick("content://picked/a", "a.bin", 10))); advanceUntilIdle()
        val going = vm.pending.first { it.isNotEmpty() }.single()
        assertEquals(Messages.CANCEL, pendingAction(going))
        vm.dismissSend(going.id)
        assertTrue(vm.pending.first { it.isEmpty() }.isEmpty())
        c.resumeSends().join()
        assertEquals(0, fake.postCalls)
        sendFiles.prune(c.pendingSends.value) // the app prunes on every change: its copy goes with it
        assertTrue(File(tmp.root, "chat-send").listFiles().orEmpty().isEmpty())
    }

    @Test fun aFileTooBigForTheRelayIsDroppedWithANotice() = runTest {
        val chat = fake.addChat("s1", "Trip", owner = me, members = listOf(me, sam))
        val api = object : RelayApi by fake {
            override suspend fun createUpload(relativePath: String, mimeType: String, sizeBytes: Long): UploadStatus {
                if (relativePath == "clip.mp4") throw RelayException(RelayErrorKind.TOO_LARGE, "too_large")
                return fake.createUpload(relativePath, mimeType, sizeBytes)
            }
        }
        val c = controller(api)
        val vm = vm(c)
        c.refresh(); advanceUntilIdle()
        vm.open(chat)
        vm.attach(listOf(pick("content://picked/v", "clip.mp4", 10), pick("content://picked/p", "photo.bin", 5))); advanceUntilIdle()
        assertEquals("clip.mp4 is too big for the relay", vm.notice.first { it != null })
        val posted = c.messages(chat).first { list -> list.any { it.author.deviceId == me.deviceId } }.single { it.author.deviceId == me.deviceId }
        assertEquals(listOf("photo.bin"), posted.attachments.map { it.name })
    }

    @Test fun mediaRetriesWhenTheRelayReconnects() = runTest {
        val c = controller()
        val vm = vm(c)
        val source = assertNotNull(vm.mediaSource)
        val before = source.connects.value
        c.onEvent(app.mjolnir.protocol.RelayEvent.Connected); advanceUntilIdle()
        assertEquals(before + 1, source.connects.value) // the bubbles key their reload on this
    }

    @Test fun sendCopiesStayWhilePreparingOrPending() {
        val root = File(tmp.root, "chat-send")
        val files = ChatSendFiles(root)
        val preparing = files.newDir()
        val queued = files.newDir().also { File(it, "x/a.bin").apply { parentFile.mkdirs(); writeText("a") } }
        files.done(queued, queued = true)
        val refused = files.newDir()
        files.done(refused, queued = false)
        assertTrue(!refused.exists()) // nothing to send: gone at once
        val stale = File(root, "left-by-a-dead-process").apply { mkdirs() }
        val send = app.mjolnir.client.PendingSend("s", "c", null, listOf(app.mjolnir.client.PendingFile("a.bin", AttachmentKind.FILE, 1, null, File(queued, "x/a.bin").path, null)), 0, 1)
        files.prune(listOf(send))
        assertTrue(preparing.exists() && queued.exists())
        assertTrue(!stale.exists())
        files.done(preparing, queued = true)
        files.prune(emptyList()) // posted (or everything reset)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }
}
