package app.mjolnir.client

import app.mjolnir.protocol.FileRef
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileTransferTest {
    private val api = FakeRelayApi()
    @TempDir lateinit var tmp: File
    private lateinit var staging: File

    @BeforeTest fun setUp() { staging = File(tmp, "staging").apply { mkdirs() } }

    @Test fun uniqueTargetCounts(@TempDir dir: File) {
        File(dir, "a.txt").createNewFile(); assertEquals("a (1).txt", uniqueTarget(dir, "a.txt").name)
        File(dir, "a (1).txt").createNewFile(); assertEquals("a (2).txt", uniqueTarget(dir, "a.txt").name)
        assertEquals("README", uniqueTarget(dir, "README").name)
        File(dir, "README").createNewFile(); assertEquals("README (1)", uniqueTarget(dir, "README").name)
    }

    @Test fun folderArrivesUnderOneUniqueTopFolder() = runTest {
        val refs = listOf(api.putBlob("1", "Photos/a.jpg", byteArrayOf(1)), api.putBlob("2", "Photos/2024/b.jpg", byteArrayOf(2)), api.putBlob("3", "c.txt", byteArrayOf(3)))
        val got = receiveFiles(api, refs, staging, folderExists = { it == "Photos" }) { _, t, _ -> t }
        assertEquals(listOf(ReceivedPath("Photos (1)", "a.jpg"), ReceivedPath("Photos (1)/2024", "b.jpg"), ReceivedPath(null, "c.txt")), got)
    }

    @Test fun publishGetsVerifiedPartsAndDeletesThem() = runTest {
        val bytes = ByteArray(5000) { it.toByte() }
        val ref = api.putBlob("x", "Docs/report.pdf", bytes)
        val seen = mutableListOf<ByteArray>()
        val progress = mutableListOf<Long>()
        receiveFiles(api, listOf(ref), staging, { false }, publish = { part, t, r ->
            assertEquals(ref, r); assertEquals(ReceivedPath("Docs", "report.pdf"), t); seen += part.readBytes()
        }, onProgress = { _, b -> progress += b })
        assertContentEquals(bytes, seen.single())
        assertEquals(5000L, progress.last())
        assertFalse(File(staging, "x.part").exists())
    }

    @Test fun receiveFilesPublishesOnlyWhenAllDownloaded() = runTest {
        val ok = api.putBlob("f1", "a.txt", byteArrayOf(1)); val missing = FileRef("f2", "b.txt", 1, "text/plain", "00", "b.txt")
        val published = mutableListOf<ReceivedPath>()
        assertFailsWith<RelayException> { receiveFiles(api, listOf(ok, missing), staging, { false }) { _, t, _ -> published += t } }
        assertTrue(published.isEmpty()); assertTrue(File(staging, "f1.part").exists())   // kept for resume
    }

    @Test fun receiveFilesResumesExistingPart() = runTest {
        val bytes = ByteArray(1000) { (it * 7).toByte() }
        val ref = api.putBlob("r", "big.bin", bytes)
        File(staging, "r.part").writeBytes(bytes.copyOf(400))
        val progress = mutableListOf<Long>()
        val got = receiveFiles(api, listOf(ref), staging, { false }, publish = { part, _, _ -> part.readBytes() },
            onProgress = { _, b -> progress += b })
        assertContentEquals(bytes, got.single())
        assertTrue(progress.first() >= 400)
    }

    @Test fun twoTopFoldersNeverCollide() = runTest {
        val refs = listOf(api.putBlob("1", "Photos/a.jpg", byteArrayOf(1)), api.putBlob("2", "Photos (1)/b.jpg", byteArrayOf(2)))
        val got = receiveFiles(api, refs, staging, folderExists = { it == "Photos" }) { _, t, _ -> t.folder }
        assertEquals(2, got.toSet().size)
        assertEquals("Photos (1)", got[0])
    }

    @Test fun walkFolderListsFilesWithRelativePaths() {
        val root = File(tmp, "Photos"); File(root, "2024").mkdirs()
        File(root, "a.jpg").writeBytes(byteArrayOf(1, 2)); File(root, "2024/b.jpg").writeBytes(byteArrayOf(3))
        val got = walkFolder(root)
        assertEquals(listOf("Photos/2024/b.jpg", "Photos/a.jpg"), got.map { it.relativePath })
        assertEquals(listOf(1L, 2L), got.map { it.sizeBytes })
        assertEquals(File(root, "a.jpg").absolutePath, got[1].path)
        assertTrue(got.all { it.mimeType.isNotEmpty() && it.uploadId == null && it.ref == null })
    }

    @Test fun walkFolderSkipsSymlinks() {
        val root = File(tmp, "Root").apply { mkdirs() }
        val outside = File(tmp, "secret.txt").apply { writeText("s") }
        File(root, "real.txt").writeText("r")
        try {
            Files.createSymbolicLink(File(root, "link.txt").toPath(), outside.toPath())
        } catch (_: Exception) { return }  // no symlink support here
        assertEquals(listOf("Root/real.txt"), walkFolder(root).map { it.relativePath })
    }

    @Test fun cleanStagingRemovesOrphanParts() {
        File(staging, "a.part").createNewFile(); File(staging, "b.part").createNewFile()
        cleanStaging(staging, setOf("a"))
        assertEquals(listOf("a.part"), staging.list()!!.toList())
    }

    @Test fun uploadResumableAgainstFakeRecordsStateAndBlob() = runTest {
        val bytes = ByteArray(2500) { it.toByte() }
        val states = mutableListOf<LocalUpload>()
        val ref = uploadResumable(api, LocalUpload("p", "Docs/x.bin", "application/octet-stream", 2500),
            { off -> bytes.inputStream().apply { skip(off) } }, { states += it }, chunkBytes = 1000)
        assertNotNull(states.first().uploadId)
        assertEquals(ref, states.last().ref)
        assertContentEquals(bytes, api.blobs.getValue(ref.id))
        assertEquals(listOf(ref), api.uploads)
        assertEquals("Docs/x.bin", ref.relativePath)
    }

    @Test fun lostUploadSessionStartsOver() = runTest {
        val bytes = ByteArray(10) { it.toByte() }
        val states = mutableListOf<LocalUpload>()
        val ref = uploadResumable(api, LocalUpload("p", "x.bin", "application/octet-stream", 10, uploadId = "gone"),
            { off -> bytes.inputStream().apply { skip(off) } }, { states += it })
        assertTrue(states.first().uploadId != "gone")
        assertContentEquals(bytes, api.blobs.getValue(ref.id))
    }

    @Test fun failUploadsWithPropagates() = runTest {
        api.failUploadsWith = RelayException(RelayErrorKind.STORAGE_FULL, "full")
        val e = assertFailsWith<RelayException> {
            uploadResumable(api, LocalUpload("p", "x.bin", "application/octet-stream", 3), { byteArrayOf(1, 2, 3).inputStream() }, {})
        }
        assertEquals(RelayErrorKind.STORAGE_FULL, e.kind)
    }

    // R14: a source that cannot be opened is reported as INVALID with the user-facing message, not NETWORK.
    @Test fun unopenableSourceIsInvalidNotNetwork() = runTest {
        val e = assertFailsWith<RelayException> {
            uploadResumable(api, LocalUpload("/nope/x.bin", "Docs/x.bin", "application/octet-stream", 3),
                { throw java.io.FileNotFoundException("/nope/x.bin") }, {})
        }
        assertEquals(RelayErrorKind.INVALID, e.kind); assertEquals(Messages.unreadable("x.bin"), e.message)
    }

    // R14: a read error mid-stream (before the chunk is sent) is also INVALID.
    @Test fun streamReadFailureIsInvalid() = runTest {
        val broken = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("disk gone") }
        val e = assertFailsWith<RelayException> {
            uploadResumable(api, LocalUpload("p", "x.bin", "application/octet-stream", 3), { broken }, {})
        }
        assertEquals(RelayErrorKind.INVALID, e.kind); assertEquals(Messages.unreadable("x.bin"), e.message)
    }

    // R14: top folders differing only in case get distinct names (Windows folders are case-insensitive).
    @Test fun topFoldersDifferingOnlyInCaseGetDistinctNames() = runTest {
        val refs = listOf(api.putBlob("1", "Photos/a.jpg", byteArrayOf(1)), api.putBlob("2", "photos/b.jpg", byteArrayOf(2)))
        val got = receiveFiles(api, refs, staging, folderExists = { false }) { _, t, _ -> t.folder!! }
        assertEquals(listOf("Photos", "photos (1)"), got)
    }
}
