package app.mjolnir.client

import app.mjolnir.protocol.SendRequest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer

class OutboxTest {
    private val dir = createTempDirectory("outbox").toFile()
    private val storeFile = File(dir, "outbox.json")
    private val api = FakeRelayApi()
    private val data = mutableMapOf<String, ByteArray>()
    private val cleaned = mutableListOf<String>()

    private fun store() = JsonFileStore(storeFile, ListSerializer(OutgoingFiles.serializer())) { emptyList() }
    private fun outbox(chunkBytes: Long = 4) = Outbox(
        store(), { api },
        open = { u, off -> data.getValue(u.path).inputStream().apply { skip(off) } },
        cleanup = { cleaned += it.path },
        chunkBytes = chunkBytes,
    )

    private fun upload(path: String, size: Int): LocalUpload {
        data[path] = ByteArray(size) { (it * 3).toByte() }
        return LocalUpload(path, "Docs/$path", "application/octet-stream", size.toLong())
    }

    private fun item(vararg files: LocalUpload, id: String = "o1") =
        OutgoingFiles(id, "pc1", "Desk PC", files.toList(), createdAtEpochMs = 1)

    private fun fail(kind: RelayErrorKind) = RelayException(kind, kind.name)

    @Test fun processUploadsThenSendsOnceAndRemoves() = runTest {
        val ob = outbox(); ob.enqueue(item(upload("a", 10), upload("b", 3)))
        val result = ob.process("o1")
        assertEquals(2, api.uploads.size)
        assertEquals(listOf(SendRequest("pc1", emptyList(), api.uploads.map { it.id })), api.sent)
        assertTrue(result is OutboxResult.Done); assertEquals("o1", result.item.id)
        assertTrue(ob.pending().isEmpty())
        assertEquals(listOf("a", "b"), cleaned)
    }

    @Test fun interruptedUploadResumesOnNextProcess() = runTest {
        val ob = outbox(); ob.enqueue(item(upload("a", 10)))
        val first = ob.process("o1") { done, _ -> if (done >= 4) api.failUploadsWith = fail(RelayErrorKind.NETWORK) }
        assertEquals(OutboxResult.Retry, first)
        val uploadId = assertNotNull(ob.get("o1")!!.files.single().uploadId)
        api.failUploadsWith = null
        assertTrue(ob.process("o1") is OutboxResult.Done)
        assertEquals(listOf(uploadId), api.uploads.map { it.id })   // same session reused, no new one created
        assertTrue(api.sessions.isEmpty())
        assertEquals(data.getValue("a").toList(), api.blobs.getValue(uploadId).toList())
    }

    @Test fun lostSendResponseIsNotSentTwice() = runTest {
        var calls = 0
        api.onSend = { if (++calls == 1) throw fail(RelayErrorKind.NETWORK) else throw fail(RelayErrorKind.ALREADY_SENT) }
        val ob = outbox(); ob.enqueue(item(upload("a", 5)))
        assertEquals(OutboxResult.Retry, ob.process("o1"))
        assertTrue(ob.get("o1")!!.files.single().ref != null)
        assertTrue(ob.process("o1") is OutboxResult.Done)
        assertEquals(1, api.uploads.size)   // not re-uploaded
        assertTrue(ob.pending().isEmpty()); assertEquals(listOf("a"), cleaned)
    }

    @Test fun permanentFailureRemovesAndReturnsItem() = runTest {
        api.onSend = { throw fail(RelayErrorKind.NOT_PAIRED) }
        val ob = outbox(); val it = item(upload("a", 5)); ob.enqueue(it)
        val result = ob.process("o1")
        assertTrue(result is OutboxResult.Failed); assertEquals(RelayErrorKind.NOT_PAIRED, result.kind)
        assertEquals("o1", result.item.id); assertEquals(it.files.size, result.item.files.size)
        assertTrue(ob.pending().isEmpty()); assertTrue(cleaned.isEmpty())
    }

    @Test fun stateSurvivesANewOutboxInstance() = runTest {
        outbox().apply { enqueue(item(upload("a", 10))) }
            .process("o1") { done, _ -> if (done >= 4) api.failUploadsWith = fail(RelayErrorKind.NETWORK) }
        val restored = outbox().pending().single().files.single()
        assertNotNull(restored.uploadId); assertEquals(null, restored.ref)
        api.failUploadsWith = null
        assertTrue(outbox().process("o1") is OutboxResult.Done)
        assertEquals(listOf(restored.uploadId), api.uploads.map { it.id })
    }

    @Test fun enqueueRejectsEmptyOrOver1000Files() {
        val ob = outbox()
        assertFailsWith<IllegalArgumentException> { ob.enqueue(item()) }
        val many = (0..1000).map { LocalUpload("p$it", "p$it", "text/plain", 1) }
        assertFailsWith<IllegalArgumentException> { ob.enqueue(item(*many.toTypedArray())) }
        assertTrue(ob.pending().isEmpty())
    }

    @Test fun allowMeteredIsPersisted() {
        outbox().enqueue(item(upload("a", 1)))
        outbox().allowMetered("o1")
        assertTrue(outbox().get("o1")!!.allowMetered)
    }

    @Test fun totalBytesSumsFiles() = assertEquals(13L, item(upload("a", 10), upload("b", 3)).totalBytes)

    @Test fun progressReachesTotal() = runTest {
        val ob = outbox(); ob.enqueue(item(upload("a", 10), upload("b", 3)))
        val seen = mutableListOf<Pair<Long, Long>>()
        ob.process("o1") { d, t -> seen += d to t }
        assertTrue(seen.all { it.second == 13L }); assertEquals(13L, seen.last().first)
        assertEquals(seen.map { it.first }.sorted(), seen.map { it.first })   // never goes backwards
    }

    @Test fun rateLimitedSendIsRetriedWithState() = runTest {
        api.onSend = { throw fail(RelayErrorKind.RATE_LIMITED) }
        val ob = outbox(); ob.enqueue(item(upload("a", 5)))
        assertEquals(OutboxResult.Retry, ob.process("o1"))
        assertEquals(1, ob.pending().size)
    }

    // The relay keeps acked files as tombstones, so a retry after a lost response is ALREADY_SENT, never INVALID.
    @Test fun lostResponseThenRecipientAckedThenRetryIsDone() = runTest {
        var calls = 0
        api.onSend = { if (++calls == 1) throw fail(RelayErrorKind.SERVER) else throw fail(RelayErrorKind.ALREADY_SENT) }
        val ob = outbox(); ob.enqueue(item(upload("a", 5)))
        assertEquals(OutboxResult.Retry, ob.process("o1"))
        val ref = ob.get("o1")!!.files.single().ref!!
        assertTrue(ob.process("o1") is OutboxResult.Done)
        assertEquals(listOf(ref.id, ref.id), api.sent.map { it.fileIds.single() })   // the same ids, no re-upload
        assertEquals(1, api.uploads.size)
        assertTrue(ob.pending().isEmpty()); assertEquals(listOf("a"), cleaned)
    }

    // INVALID is a real failure, even after an ambiguous earlier send.
    @Test fun invalidAfterAmbiguousSendFails() = runTest {
        var calls = 0
        api.onSend = { if (++calls == 1) throw fail(RelayErrorKind.SERVER) else throw fail(RelayErrorKind.INVALID) }
        val ob = outbox(); ob.enqueue(item(upload("a", 5)))
        assertEquals(OutboxResult.Retry, ob.process("o1"))
        val result = ob.process("o1")
        assertTrue(result is OutboxResult.Failed); assertEquals(RelayErrorKind.INVALID, result.kind)
        assertEquals(null, result.message)
        assertTrue(cleaned.isEmpty())
    }

    // The relay dropped the unsent files (1 h TTL) between upload and send: upload again, then send the fresh ids.
    @Test fun filesExpiredBeforeSendAreUploadedAgain() = runTest {
        var calls = 0
        api.onSend = { if (++calls == 1) throw fail(RelayErrorKind.NETWORK) else app.mjolnir.protocol.SendResponse("del-1") }
        val ob = outbox(); ob.enqueue(item(upload("a", 10), upload("b", 3)))
        assertEquals(OutboxResult.Retry, ob.process("o1"))
        val stale = ob.get("o1")!!.files.map { it.ref!!.id }
        stale.forEach(api::expire)
        val result = ob.process("o1")
        assertTrue(result is OutboxResult.Done)
        val fresh = api.sent.last().fileIds
        assertEquals(2, fresh.size); assertTrue(fresh.none { it in stale })
        assertEquals(data.getValue("a").toList(), api.blobs.getValue(fresh[0]).toList())
        assertEquals(data.getValue("b").toList(), api.blobs.getValue(fresh[1]).toList())
    }

    // Only the expired file is uploaded again; a file the relay still holds keeps its ref.
    @Test fun onlyExpiredFilesAreUploadedAgain() = runTest {
        var calls = 0
        api.onSend = { if (++calls == 1) throw fail(RelayErrorKind.NETWORK) else app.mjolnir.protocol.SendResponse("del-1") }
        val ob = outbox(); ob.enqueue(item(upload("a", 10), upload("b", 3)))
        ob.process("o1")
        val (a, b) = ob.get("o1")!!.files.map { it.ref!!.id }
        api.expire(a)
        assertTrue(ob.process("o1") is OutboxResult.Done)
        val sentIds = api.sent.last().fileIds
        assertTrue(sentIds[0] != a); assertEquals(b, sentIds[1])
        assertEquals(listOf(b, sentIds[0]), api.uploads.map { it.id })   // b kept; a uploaded again
    }

    @Test fun outboxWrittenByAnOlderVersionStillLoads() {
        storeFile.writeText(
            """[{"id":"o1","to":"pc1","toName":"Desk PC","files":[],"createdAtEpochMs":1,"sendAttempted":true}]"""
        )
        assertEquals("o1", outbox().pending().single().id)
    }

    // Item 7: a local read failure says which file instead of "the relay had a problem".
    @Test fun unreadableFileFailureCarriesItsMessage() = runTest {
        val src = File(dir, "photo.jpg").apply { writeBytes(ByteArray(8)) }
        val ob = Outbox(store(), { api }, open = { u, off -> File(u.path).inputStream().apply { skip(off) } })
        ob.enqueue(item(LocalUpload(src.absolutePath, "Pics/photo.jpg", "image/jpeg", 8)))
        src.delete()
        val result = ob.process("o1")
        assertTrue(result is OutboxResult.Failed)
        assertEquals("Couldn't read photo.jpg", result.message)
    }

    // R14: a source file that vanished is a permanent failure, not an endless NETWORK retry.
    @Test fun deletedSourceFileFailsWithInvalid() = runTest {
        val src = File(dir, "gone.bin").apply { writeBytes(ByteArray(8) { it.toByte() }) }
        val ob = Outbox(store(), { api }, open = { u, off -> File(u.path).inputStream().apply { skip(off) } })
        ob.enqueue(item(LocalUpload(src.absolutePath, "gone.bin", "application/octet-stream", 8)))
        src.delete()
        val result = ob.process("o1")
        assertTrue(result is OutboxResult.Failed); assertEquals(RelayErrorKind.INVALID, result.kind)
        assertTrue(ob.pending().isEmpty()); assertTrue(api.sent.isEmpty())
    }

    @Test fun unknownIdIsRetryAndHarmless() = runTest { assertEquals(OutboxResult.Retry, outbox().process("nope")) }
}
