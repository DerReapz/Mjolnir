package app.mjolnir.client

import app.mjolnir.protocol.FileRef
import app.mjolnir.server.FileStorage
import app.mjolnir.server.RelayDeps
import app.mjolnir.server.Store
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Serves [bytes] from [offset] and throws IOException once [failAfter] bytes have been read. */
private class FailingStream(private val bytes: ByteArray, offset: Long, private val failAfter: Int) : InputStream() {
    private var pos = offset.toInt()
    private var served = 0
    override fun read(): Int {
        if (served >= failAfter) throw IOException("link dropped")
        if (pos >= bytes.size) return -1
        served++
        return bytes[pos++].toInt() and 0xFF
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (served >= failAfter) throw IOException("link dropped")
        if (pos >= bytes.size) return -1
        val n = minOf(len, bytes.size - pos, failAfter - served)
        System.arraycopy(bytes, pos, b, off, n)
        pos += n; served += n
        return n
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun tempTarget(): File = File.createTempFile("dl-", null).apply { delete(); deleteOnExit() }

class FileClientTest {
    private suspend fun ClientTestScope.upload(api: RelayApi, bytes: ByteArray, name: String = "photo.jpg"): FileRef =
        uploadResumable(api, LocalUpload("mem", name, "image/jpeg", bytes.size.toLong()),
            { off -> bytes.inputStream().apply { skip(off) } }, {}, chunkBytes = 1_048_576)

    @Test fun chunkedUploadThenDownloadRoundTrip() = clientTest {
        val (pc, phone) = pairedDevices(); val bytes = Random(1).nextBytes(3_000_000)
        val f = LocalUpload("mem", "photo.jpg", "image/jpeg", bytes.size.toLong())
        val ref = uploadResumable(phone.api, f, { off -> bytes.inputStream().apply { skip(off) } }, {}, chunkBytes = 1_048_576)
        assertEquals(sha256Hex(bytes), ref.sha256)
        phone.api.send(pc.id, emptyList(), listOf(ref.id))
        val target = tempTarget()
        pc.api.downloadFile(pc.api.inbox().single().files.single(), target); assertContentEquals(bytes, target.readBytes())
    }

    @Test fun uploadResumesAfterInterruption() = clientTest {
        val (pc, phone) = pairedDevices(); val bytes = Random(2).nextBytes(3_000_000)
        val f = LocalUpload("mem", "video.mp4", "video/mp4", bytes.size.toLong())
        var state = f
        assertKind(RelayErrorKind.NETWORK) {
            uploadResumable(phone.api, f, { off -> FailingStream(bytes, off, 1_500_000) }, { state = it }, chunkBytes = 1_048_576)
        }
        assertNotNull(state.uploadId)
        val progress = mutableListOf<Long>()
        val ref = uploadResumable(phone.api, state, { off -> bytes.inputStream().apply { skip(off) } }, { state = it },
            onProgress = { progress += it }, chunkBytes = 1_048_576)
        assertTrue(progress.first() >= 1_048_576, "first progress ${progress.first()}")
        assertEquals(bytes.size.toLong(), progress.last())
        assertEquals(ref, state.ref)
        assertEquals(sha256Hex(bytes), ref.sha256)
        // A finished state returns the ref without touching the relay.
        assertEquals(ref, uploadResumable(phone.api, state, { error("must not open") }, {}))
        phone.api.send(pc.id, emptyList(), listOf(ref.id))
        val target = tempTarget()
        pc.api.downloadFile(ref, target); assertContentEquals(bytes, target.readBytes())
    }

    @Test fun downloadResumesFromPartialFile() = clientTest {
        val (pc, phone) = pairedDevices(); val bytes = Random(3).nextBytes(3_000_000)
        val ref = upload(phone.api, bytes)
        phone.api.send(pc.id, emptyList(), listOf(ref.id))
        val target = tempTarget().apply { writeBytes(bytes.copyOf(1_000_000)) }
        val progress = mutableListOf<Long>()
        pc.api.downloadFile(ref, target) { progress += it }
        assertContentEquals(bytes, target.readBytes())
        assertTrue(progress.first() >= 1_000_000, "first progress ${progress.first()}")
        assertEquals(bytes.size.toLong(), progress.last())
    }

    @Test fun completePartIsVerifiedWithoutRefetching() = clientTest {
        val (pc, phone) = pairedDevices(); val bytes = Random(4).nextBytes(10_000)
        val ref = upload(phone.api, bytes)
        phone.api.send(pc.id, emptyList(), listOf(ref.id))
        val target = tempTarget().apply { writeBytes(bytes) }
        pc.api.downloadFile(ref, target)
        assertContentEquals(bytes, target.readBytes())
    }

    @Test fun oversizedPartStartsOver() = clientTest {
        val (pc, phone) = pairedDevices(); val bytes = Random(5).nextBytes(10_000)
        val ref = upload(phone.api, bytes)
        phone.api.send(pc.id, emptyList(), listOf(ref.id))
        val target = tempTarget().apply { writeBytes(Random(6).nextBytes(20_000)) }
        pc.api.downloadFile(ref, target)
        assertContentEquals(bytes, target.readBytes())
    }

    @Test fun tamperedBlobIsRejected() = clientTest {
        val (pc, phone) = pairedDevices(); val bytes = Random(7).nextBytes(200_000)
        val ref = upload(phone.api, bytes)
        phone.api.send(pc.id, emptyList(), listOf(ref.id))
        deps.files.file(ref.id).writeBytes(Random(8).nextBytes(bytes.size))
        val target = tempTarget()
        assertKind(RelayErrorKind.INVALID) { pc.api.downloadFile(ref, target) }
        assertFalse(target.exists())
    }

    @Test fun uploadOverLimitIsTooLarge() = clientTest(
        RelayDeps(store = Store("jdbc:sqlite::memory:"), files = FileStorage(createTempDirectory("mj").toFile(), maxFileBytes = 10)),
    ) {
        val phone = newDevice("P", app.mjolnir.protocol.DeviceKind.PHONE)
        assertKind(RelayErrorKind.TOO_LARGE) { phone.api.createUpload("big.bin", "application/octet-stream", 11) }
    }

    @Test fun downloadOfUnknownFileIsNotFound() = clientTest {
        val (pc, _) = pairedDevices()
        val target = tempTarget()
        assertKind(RelayErrorKind.NOT_FOUND) { pc.api.downloadFile(FileRef("nope", "x", 1, "text/plain", "00"), target) }
    }

    @Test fun staleChunkReturnsServerStatus() = clientTest {
        val (_, phone) = pairedDevices(); val bytes = Random(9).nextBytes(100)
        val s = phone.api.createUpload("x.bin", "application/octet-stream", 100)
        assertEquals(50, phone.api.uploadChunk(s.uploadId, 0, 100, bytes, 50).offset)
        val conflict = phone.api.uploadChunk(s.uploadId, 0, 100, bytes, 50)
        assertEquals(50, conflict.offset)
        val done = phone.api.uploadChunk(s.uploadId, 50, 100, bytes.copyOfRange(50, 100), 50)
        assertEquals(sha256Hex(bytes), done.file?.sha256)
        assertEquals(done.file, phone.api.uploadStatus(s.uploadId).file)
    }
}
