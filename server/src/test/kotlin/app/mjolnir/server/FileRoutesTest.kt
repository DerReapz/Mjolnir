package app.mjolnir.server

import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.SendRequest
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.UploadStatus
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileRoutesTest {
    private fun storage(maxFileBytes: Long = Limits.MAX_FILE_BYTES, maxTotalBytes: Long = 2_147_483_648L) =
        FileStorage(createTempDirectory("mjolnir-files").toFile(), maxFileBytes, maxTotalBytes)

    @Test fun chunkedUploadProducesRef() = relayTest {
        val phone = register(); val bytes = ByteArray(20) { it.toByte() }
        val s = createUpload(phone, "Photos/a.jpg", "image/jpeg", 20)
        assertEquals(8, putChunk(phone, s.uploadId, bytes, 0, 8).offset)
        assertEquals(16, putChunk(phone, s.uploadId, bytes, 8, 16).offset)
        val done = putChunk(phone, s.uploadId, bytes, 16, 20)
        assertEquals(FileRef(s.uploadId, "a.jpg", 20, "image/jpeg", sha256Hex(bytes), "Photos/a.jpg"), done.file)
    }

    @Test fun resumeContinuesFromStoredOffset() = relayTest {
        val phone = register(); val bytes = ByteArray(20) { (it * 7).toByte() }
        val s = createUpload(phone, "a.bin", "application/octet-stream", 20)
        putChunk(phone, s.uploadId, bytes, 0, 8)
        val status = client.get("/v1/uploads/${s.uploadId}") { bearerAuth(phone.token) }.body<UploadStatus>()
        assertEquals(UploadStatus(s.uploadId, 8), status)
        val done = putChunk(phone, s.uploadId, bytes, 8, 20)
        assertEquals(20, done.offset)
        val got = client.get("/v1/files/${s.uploadId}") { bearerAuth(phone.token) }.readRawBytes()
        assertContentEquals(bytes, got)
    }

    @Test fun wrongStartIsConflictWithCurrentOffset() = relayTest {
        val phone = register(); val bytes = ByteArray(20)
        val s = createUpload(phone, "a.bin", "application/octet-stream", 20)
        putChunk(phone, s.uploadId, bytes, 0, 8)
        val r = putChunkResponse(phone, s.uploadId, bytes, 0, 8)
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals(UploadStatus(s.uploadId, 8), r.body<UploadStatus>())
    }

    @Test fun oversizeIsRejectedAtCreate() = relayTest(files = storage(maxFileBytes = 10)) {
        val phone = register()
        assertEquals(HttpStatusCode.PayloadTooLarge, createUploadResponse(phone, "a.bin", "application/octet-stream", 11).status)
        assertEquals(HttpStatusCode.Created, createUploadResponse(phone, "a.bin", "application/octet-stream", 10).status)
    }

    @Test fun fullRelayCountsOpenUploads() = relayTest(files = storage(maxTotalBytes = 10)) {
        val phone = register()
        assertEquals(HttpStatusCode.Created, createUploadResponse(phone, "a.bin", "application/octet-stream", 8).status)
        assertEquals(HttpStatusCode.InsufficientStorage, createUploadResponse(phone, "b.bin", "application/octet-stream", 8).status)
    }

    @Test fun relativePathIsSanitized() = relayTest {
        val phone = register()
        val ref = upload(phone, "..\\..\\x/CON/a.txt", "hi".toByteArray(), "text/plain")
        assertEquals("x/_CON/a.txt", ref.relativePath)
        assertEquals("a.txt", ref.name)
    }

    @Test fun filesTravelWithTheDelivery() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val bytes = "hello, mjolnir".toByteArray()
        val ref = upload(phone, "Docs/hello.txt", bytes, "text/plain")
        assertEquals(HttpStatusCode.Accepted, sendFiles(phone, pc.deviceId, listOf(ref.id)).status)
        val delivery = inbox(pc).single()
        assertEquals(listOf(ref), delivery.files)
        assertTrue(delivery.tabs.isEmpty())
        val r = client.get("/v1/files/${ref.id}") { bearerAuth(pc.token) }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("\"${ref.sha256}\"", r.headers[HttpHeaders.ETag])
        assertContentEquals(bytes, r.readRawBytes())
    }

    @Test fun rangeDownloadReturnsTail() = relayTest {
        val phone = register(); val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val ref = upload(phone, "five.bin", bytes)
        val r = client.get("/v1/files/${ref.id}") { bearerAuth(phone.token); header(HttpHeaders.Range, "bytes=2-") }
        assertEquals(HttpStatusCode.PartialContent, r.status)
        assertEquals("bytes 2-4/5", r.headers[HttpHeaders.ContentRange])
        assertEquals("\"${ref.sha256}\"", r.headers[HttpHeaders.ETag])
        assertContentEquals(byteArrayOf(3, 4, 5), r.readRawBytes())
        val past = client.get("/v1/files/${ref.id}") { bearerAuth(phone.token); header(HttpHeaders.Range, "bytes=5-") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, past.status)
    }

    @Test fun strangersCannotDownloadOrResume() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val stranger = register("Stranger")
        val ref = upload(phone, "a.bin", ByteArray(4))
        sendFiles(phone, pc.deviceId, listOf(ref.id))
        val open = createUpload(phone, "b.bin", "application/octet-stream", 4)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/files/${ref.id}") { bearerAuth(stranger.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/uploads/${open.uploadId}") { bearerAuth(stranger.token) }.status)
        assertEquals(HttpStatusCode.NotFound, putChunkResponse(stranger, open.uploadId, ByteArray(4), 0, 4).status)
    }

    @Test fun resendingTheSameFilesIsConflict() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val a = upload(phone, "a.bin", ByteArray(3)); val b = upload(phone, "b.bin", ByteArray(3))
        assertEquals(HttpStatusCode.Accepted, sendFiles(phone, pc.deviceId, listOf(a.id, b.id)).status)
        assertEquals(HttpStatusCode.Conflict, sendFiles(phone, pc.deviceId, listOf(a.id, b.id)).status)
        assertEquals(1, inbox(pc).size)
    }

    @Test fun cannotSendSomeoneElsesFile() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val ref = upload(phone, "a.bin", ByteArray(3))
        assertEquals(HttpStatusCode.BadRequest, sendFiles(pc, phone.deviceId, listOf(ref.id)).status)
        assertEquals(HttpStatusCode.BadRequest, sendFiles(phone, pc.deviceId, listOf("no-such-file")).status)
    }

    @Test fun invalidSendShapesAreRejected() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val ref = upload(phone, "a.bin", ByteArray(3))
        assertEquals(HttpStatusCode.BadRequest, sendFiles(phone, pc.deviceId, emptyList()).status)
        val both = client.post("/v1/send") {
            bearerAuth(phone.token); contentType(ContentType.Application.Json)
            setBody(SendRequest(pc.deviceId, tabs = listOf(Tab("https://a.com")), fileIds = listOf(ref.id)))
        }
        assertEquals(HttpStatusCode.BadRequest, both.status)
        assertEquals(HttpStatusCode.BadRequest, sendFiles(phone, pc.deviceId, List(1001) { ref.id }).status)
    }

    @Test fun ackDeletesTheBlob() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val ref = upload(phone, "a.bin", ByteArray(3))
        sendFiles(phone, pc.deviceId, listOf(ref.id))
        val id = inbox(pc).single().id
        assertTrue(deps.files.file(ref.id).exists())
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/inbox/$id") { bearerAuth(pc.token) }.status)
        assertFalse(deps.files.file(ref.id).exists())
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/files/${ref.id}") { bearerAuth(pc.token) }.status)
    }

    @Test fun resendAfterAckIsAlreadySent() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val ref = upload(phone, "a.bin", ByteArray(3))
        assertEquals(HttpStatusCode.Accepted, sendFiles(phone, pc.deviceId, listOf(ref.id)).status)
        val id = inbox(pc).single().id
        client.delete("/v1/inbox/$id") { bearerAuth(pc.token) }
        // The sender's response was lost and it retries after the PC acked: the relay still remembers the ids.
        assertEquals(HttpStatusCode.Conflict, sendFiles(phone, pc.deviceId, listOf(ref.id)).status)
        assertEquals(emptyList(), inbox(pc))
        // A tombstone is not downloadable by anyone.
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/files/${ref.id}") { bearerAuth(phone.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/files/${ref.id}") { bearerAuth(pc.token) }.status)
        // The tombstone outlives the unsent-file TTL and goes once the delivery TTL has passed since the ack.
        clock.now += Limits.UNSENT_FILE_TTL_MS + 1
        purge(deps)
        assertEquals(1, store.rowCount("files"))
        assertEquals(HttpStatusCode.Conflict, sendFiles(phone, pc.deviceId, listOf(ref.id)).status)
        clock.now += Limits.DELIVERY_TTL_MS
        purge(deps)
        assertEquals(0, store.rowCount("files"))
    }

    @Test fun tombstoneCannotBeSentToAnotherDevice() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val ref = upload(phone, "a.bin", ByteArray(3))
        sendFiles(phone, pc.deviceId, listOf(ref.id))
        client.delete("/v1/inbox/${inbox(pc).single().id}") { bearerAuth(pc.token) }
        val fresh = upload(phone, "b.bin", ByteArray(3))
        assertEquals(HttpStatusCode.BadRequest, sendFiles(phone, pc.deviceId, listOf(ref.id, fresh.id)).status)
    }

    @Test fun incompleteUploadsArePurgedAfterADay() = relayTest {
        val phone = register(); val bytes = ByteArray(20)
        val s = createUpload(phone, "a.bin", "application/octet-stream", 20)
        putChunk(phone, s.uploadId, bytes, 0, 8)
        val part = java.io.File(deps.files.dir, "${s.uploadId}.part")
        assertTrue(part.exists())
        clock.now += 86_399_999
        purge(deps)
        assertTrue(part.exists())
        clock.now += 2
        purge(deps)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/uploads/${s.uploadId}") { bearerAuth(phone.token) }.status)
        assertFalse(part.exists())
        assertEquals(0, store.rowCount("uploads"))
    }

    @Test fun unsentFilesArePurgedAfterAnHour() = relayTest {
        val phone = register()
        val ref = upload(phone, "a.bin", ByteArray(3))
        clock.now += 3_599_999
        purge(deps)
        assertTrue(deps.files.file(ref.id).exists())
        clock.now += 2
        purge(deps)
        assertFalse(deps.files.file(ref.id).exists())
        assertEquals(0, store.rowCount("files"))
    }

    @Test fun expiredDeliveryBlobsArePurged() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val old = upload(phone, "old.bin", ByteArray(3))
        sendFiles(phone, pc.deviceId, listOf(old.id))
        clock.now += 604_800_001
        val fresh = upload(phone, "fresh.bin", ByteArray(3))
        sendFiles(phone, pc.deviceId, listOf(fresh.id))
        purge(deps)
        assertFalse(deps.files.file(old.id).exists())
        assertTrue(deps.files.file(fresh.id).exists())
        assertEquals(1, store.rowCount("deliveries"))
        assertEquals(1, store.rowCount("files"))
        assertEquals(listOf(fresh), inbox(pc).single().files)
    }

    @Test fun fullSizeChunkIsAcceptedAndOversizeChunkIsRejected() =
        relayTest(files = storage(maxFileBytes = 3 * Limits.UPLOAD_CHUNK_BYTES)) {
            val phone = register()
            val chunk = Limits.UPLOAD_CHUNK_BYTES.toInt()
            val total = 2L * chunk + 2
            val bytes = ByteArray(total.toInt()) { (it % 251).toByte() }
            val s = createUpload(phone, "big.bin", "application/octet-stream", total)
            assertEquals(chunk.toLong(), putChunk(phone, s.uploadId, bytes, 0, chunk).offset)
            val tooBig = putChunkResponse(phone, s.uploadId, bytes, chunk, 2 * chunk + 1)
            assertEquals(HttpStatusCode.BadRequest, tooBig.status)
            assertEquals(chunk.toLong(), client.get("/v1/uploads/${s.uploadId}") { bearerAuth(phone.token) }.body<UploadStatus>().offset)
        }

    @Test fun malformedContentRangeIsRejected() = relayTest {
        val phone = register(); val bytes = ByteArray(10)
        val s = createUpload(phone, "a.bin", "application/octet-stream", 10)
        val noHeader = client.put("/v1/uploads/${s.uploadId}") { bearerAuth(phone.token); setBody(bytes) }
        assertEquals(HttpStatusCode.BadRequest, noHeader.status)
        assertEquals(HttpStatusCode.BadRequest, putChunkResponse(phone, s.uploadId, bytes, 0, 10, total = 11).status)
    }

    @Test fun bodyLongerOrShorterThanContentRangeIsRejected() = relayTest {
        val phone = register()
        val s = createUpload(phone, "a.bin", "application/octet-stream", 10)
        val long = client.put("/v1/uploads/${s.uploadId}") {
            bearerAuth(phone.token); header(HttpHeaders.ContentRange, "bytes 0-3/10"); setBody(ByteArray(9))
        }
        assertEquals(HttpStatusCode.BadRequest, long.status)
        val short = client.put("/v1/uploads/${s.uploadId}") {
            bearerAuth(phone.token); header(HttpHeaders.ContentRange, "bytes 0-7/10"); setBody(ByteArray(3))
        }
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertEquals(0, client.get("/v1/uploads/${s.uploadId}") { bearerAuth(phone.token) }.body<UploadStatus>().offset)
    }

    @Test fun jsonBodyLimitStillAppliesToUploadCreate() = relayTest {
        val phone = register()
        val status = client.post("/v1/uploads") {
            bearerAuth(phone.token); contentType(ContentType.Application.Json)
            setBody("x".repeat(1_048_577))
        }.status
        assertEquals(HttpStatusCode.PayloadTooLarge, status)
    }
}
