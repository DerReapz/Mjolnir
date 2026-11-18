package app.mjolnir.desktop

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Tab
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class SendStateTest {
    @Test fun pastedTextSelectsAllLinks() = assertEquals(setOf("https://a.com", "https://b.com"),
        SendState().withText("see https://a.com and https://b.com").selected)

    @Test fun togglingDeselects() = assertEquals(listOf(Tab("https://b.com")),
        SendState().withText("https://a.com https://b.com").toggle("https://a.com").tabs())

    @Test fun targetDefaultsToLastPhoneIfStillPaired() {
        val p1 = Peer("p1", "Pixel", DeviceKind.PHONE); val p2 = Peer("p2", "Galaxy", DeviceKind.PHONE)
        assertEquals("p2", SendState().withPhones(listOf(p1, p2), "p2").targetId)
        assertEquals("p1", SendState().withPhones(listOf(p1, p2), "gone").targetId)
        assertNull(SendState().withPhones(emptyList(), "p2").targetId)
    }

    @Test fun cannotSendWithoutTargetOrSelection() {
        assertFalse(SendState().withText("https://a.com").canSend)
        assertFalse(SendState().withPhones(listOf(Peer("p", "P", DeviceKind.PHONE)), null).canSend)
    }

    @Test fun filesAloneCanBeSent(@TempDir dir: File) {
        val f = File(dir, "a.pdf").apply { writeText("x") }
        val s = SendState().withPhones(listOf(Peer("p", "P", DeviceKind.PHONE)), null).withFiles(listOf(f, f))
        assertTrue(s.canSend); assertEquals(listOf(f.path), s.files.map { it.path })
    }

    @Test fun singleOversizeFileIsRefused(@TempDir dir: File) {
        val big = File(dir, "big.iso").apply { RandomAccessFile(this, "rw").use { it.setLength(Limits.MAX_FILE_BYTES + 1) } }
        val s = SendState().withFiles(listOf(big))
        assertTrue(s.files.isEmpty()); assertEquals("big.iso is too big (max 100 MB)", s.notice)
    }

    @Test fun foldersAreExpandedAndOversizeFilesLeftOut(@TempDir dir: File) {
        val photos = File(dir, "Photos"); File(photos, "2024").mkdirs()
        File(photos, "a.jpg").writeText("a"); File(photos, "2024/b.jpg").writeText("b")
        RandomAccessFile(File(photos, "big.iso"), "rw").use { it.setLength(Limits.MAX_FILE_BYTES + 1) }
        val s = SendState().withFiles(listOf(photos))
        assertEquals(listOf("Photos/2024/b.jpg", "Photos/a.jpg"), s.files.map { it.relativePath })
        assertEquals("1 file over 100 MB was left out", s.notice)
    }

    // extra: a folder over 1000 files is refused whole
    @Test fun hugeFolderIsRefused(@TempDir dir: File) {
        val many = File(dir, "Many").apply { mkdirs() }
        repeat(Limits.MAX_FILES_PER_SEND + 1) { File(many, "f$it.txt").writeText("x") }
        val s = SendState().withFiles(listOf(many))
        assertTrue(s.files.isEmpty()); assertEquals("Many has too many files (max 1000)", s.notice)
    }

    // extra: removing a file drops it; selecting a target works
    @Test fun removeFileAndSelectTarget(@TempDir dir: File) {
        val a = File(dir, "a.txt").apply { writeText("a") }; val b = File(dir, "b.txt").apply { writeText("b") }
        val p1 = Peer("p1", "Pixel", DeviceKind.PHONE); val p2 = Peer("p2", "Galaxy", DeviceKind.PHONE)
        val s = SendState().withFiles(listOf(a, b)).removeFile(a.path).withPhones(listOf(p1, p2), null).selectTarget("p2")
        assertEquals(listOf(b.path), s.files.map { it.path }); assertEquals("p2", s.targetId)
    }
}

class GroupForSendingTest {
    private fun up(rel: String) = app.mjolnir.client.LocalUpload("/x/$rel", rel, "text/plain", 1)

    // folders are never split; loose files fill groups
    @Test fun keepsFoldersTogether() {
        val a = (1..3).map { up("A/$it") }; val b = (1..3).map { up("B/$it") }; val loose = up("c.txt")
        val groups = groupForSending(a + b + loose, max = 4)
        assertEquals(listOf(a, b + loose), groups)
    }
}
