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

    // R8: the PC acked before the lost response reached us, so the relay forgot the file ids → INVALID means Done.
    @Test fun invalidAfterAmbiguousSendFailureCountsAsDone() = runTest {
        var calls = 0
        api.onSend = { if (++calls == 1) throw fail(RelayErrorKind.SERVER) else throw fail(RelayErrorKind.INVALID) }
        val ob = outbox(); ob.enqueue(item(upload("a", 5)))
        assertEquals(OutboxResult.Retry, ob.process("o1"))
        assertTrue(ob.get("o1")!!.sendAttempted)
        assertTrue(ob.process("o1") is OutboxResult.Done)
        assertTrue(ob.pending().isEmpty()); assertEquals(listOf("a"), cleaned)
    }

    // R8: INVALID with no earlier ambiguous send (or only a definite RATE_LIMITED refusal) is a real failure.
    @Test fun invalidWithoutAmbiguousSendFails() = runTest {
        var calls = 0
        api.onSend = { if (++calls == 1) throw fail(RelayErrorKind.RATE_LIMITED) else throw fail(RelayErrorKind.INVALID) }
        val ob = outbox(); ob.enqueue(item(upload("a", 5)))
        assertEquals(OutboxResult.Retry, ob.process("o1"))
        assertEquals(false, ob.get("o1")!!.sendAttempted)
        val result = ob.process("o1")
        assertTrue(result is OutboxResult.Failed); assertEquals(RelayErrorKind.INVALID, result.kind)
        assertTrue(cleaned.isEmpty())
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
