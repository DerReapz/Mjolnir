package app.mjolnir.desktop

import app.mjolnir.client.DestinationKey
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.SpaceRef
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.extractUrls
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class SendStateTest {
    @Test fun pastedTextSelectsAllLinks() = assertEquals(setOf("https://a.com", "https://b.com"),
        SendState().withText("see https://a.com and https://b.com").selected)

    @Test fun defaultTargetIsNeverTheGuest() {
        val guest = Peer("g", "Guest", DeviceKind.GUEST); val pc = Peer("pc1", "Desk", DeviceKind.PC)
        assertEquals("pc1", SendState().withTargets(listOf(guest, pc), null).targetId)
        assertEquals("pc1", SendState().withTargets(listOf(guest, pc), "gone").targetId)
        assertNull(SendState().withTargets(listOf(guest), null).targetId)
    }

    @Test fun togglingDeselects() = assertEquals(listOf(Tab("https://b.com")),
        SendState().withText("https://a.com https://b.com").toggle("https://a.com").tabs())

    @Test fun pcsAndPhonesAreBothTargetsAndLastTargetIsRemembered() {
        val pc = Peer("pc1", "Desk", DeviceKind.PC); val ph = Peer("p1", "Pixel", DeviceKind.PHONE)
        val s = SendState().withTargets(listOf(ph, pc), "pc1")
        assertEquals(listOf(ph, pc), s.targets)
        assertEquals("pc1", s.targetId)
        assertEquals(pc, s.target)
        assertEquals("p1", s.selectTarget("p1").targetId)
    }

    private val mine = Peer("pc1", "Desk", DeviceKind.PC)
    private val friendInFriends = Peer("f", "Fred", DeviceKind.PC, SpaceRef("s1", "Friends"))
    private val friendInWork = Peer("f", "Fred", DeviceKind.PC, SpaceRef("s2", "Work"))

    @Test fun selectingHighlightsAllCardsOfDevice() {
        val s = SendState().withTargets(listOf(mine, friendInFriends, friendInWork), null).selectTarget(DestinationKey("f", "s2"))
        assertEquals(DestinationKey("f", "s2"), s.targetKey)
        assertEquals(listOf(friendInFriends, friendInWork), s.targets.filter { s.isSelected(it) })
        assertFalse(s.isSelected(mine))
    }

    @Test fun sendCarriesClickedSpace() {
        val base = SendState().withTargets(listOf(mine, friendInFriends, friendInWork), null)
        assertEquals("s2", base.selectTarget(DestinationKey("f", "s2")).target?.space?.id)
        assertEquals("s1", base.selectTarget(DestinationKey("f", "s1")).target?.space?.id)
        assertNull(base.selectTarget(DestinationKey("pc1", null)).target?.space)
        assertEquals(base, base.selectTarget(DestinationKey("f", "gone"))) // unknown card: unchanged
    }

    @Test fun lastUsedDeviceSelectsItsMyDevicesCardFirst() {
        val paired = Peer("f", "Fred", DeviceKind.PC)
        val s = SendState().withTargets(listOf(friendInFriends, paired), "f")
        assertEquals(DestinationKey("f", null), s.targetKey)
        assertEquals(DestinationKey("f", "s1"), SendState().withTargets(listOf(friendInFriends), "f").targetKey)
    }

    @Test fun targetDefaultsToLastPhoneIfStillPaired() {
        val p1 = Peer("p1", "Pixel", DeviceKind.PHONE); val p2 = Peer("p2", "Galaxy", DeviceKind.PHONE)
        assertEquals("p2", SendState().withTargets(listOf(p1, p2), "p2").targetId)
        assertEquals("p1", SendState().withTargets(listOf(p1, p2), "gone").targetId)
        assertNull(SendState().withTargets(emptyList(), "p2").targetId)
    }

    @Test fun cannotSendWithoutTargetOrSelection() {
        assertFalse(SendState().withText("https://a.com").canSend)
        assertFalse(SendState().withTargets(listOf(Peer("p", "P", DeviceKind.PHONE)), null).canSend)
    }

    @Test fun filesAloneCanBeSent(@TempDir dir: File) {
        val f = File(dir, "a.pdf").apply { writeText("x") }
        val s = SendState().withTargets(listOf(Peer("p", "P", DeviceKind.PHONE)), null).withFiles(listOf(f, f))
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
        val s = SendState().withFiles(listOf(a, b)).removeFile(a.path).withTargets(listOf(p1, p2), null).selectTarget("p2")
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

class SameNameFoldersTest {
    private fun photos(parent: File, file: String) = File(parent, "Photos").apply { mkdirs(); File(this, file).writeText(file) }

    // two different folders named Photos stay separate: separate sends, separate rows, Remove removes one
    @Test fun sameNamedFoldersStaySeparate(@TempDir dir: File) {
        val a = photos(File(dir, "one"), "a.jpg"); val b = photos(File(dir, "two"), "b.jpg")
        val s = SendState().withFiles(listOf(a, b))
        assertEquals(2, s.files.size)
        assertEquals(listOf(listOf("Photos/a.jpg"), listOf("Photos/b.jpg")), groupForSending(s.files).map { g -> g.map { it.relativePath } })
        val rows = app.mjolnir.desktop.ui.fileGroups(s.files)
        assertEquals(listOf("Photos", "Photos"), rows.map { it.name })
        val afterRemove = rows[0].files.fold(s) { st, f -> st.removeFile(f.path) }
        assertEquals(listOf(File(b, "b.jpg").path), afterRemove.files.map { it.path })
    }

    // the walk stops past the limit instead of reading the whole tree
    @Test fun walkStopsPastLimit(@TempDir dir: File) {
        val f = File(dir, "F").apply { mkdirs() }; repeat(5) { File(f, "$it.txt").writeText("x") }
        assertNull(walkFolderLimited(f, 3))
        assertEquals(5, walkFolderLimited(f, 5)!!.size)
        assertEquals("F has too many files (max 1000)", scanFiles(listOf(f), maxFiles = 3).single().refusal)
    }

    @Test fun summaryCountsSelectedAndSize() {
        val mb = 1024L * 1024
        val s = SendState().withText("https://a.com https://b.com").toggle("https://b.com").copy(
            files = listOf(
                app.mjolnir.client.LocalUpload("/x/Photos/a", "Photos/a", "x/y", 3 * mb),
                app.mjolnir.client.LocalUpload("/x/Photos/b", "Photos/b", "x/y", 2 * mb),
                app.mjolnir.client.LocalUpload("/y/c.txt", "c.txt", "x/y", 1 * mb),
            ),
        )
        val keys = setOf(uploadUnitKey(s.files[0]), uploadUnitKey(s.files[2]))
        assertEquals("3 of 4 selected · 6 MB", s.copy(selected = s.selected + keys).summary)
    }

    private fun upload(path: String, rel: String, size: Long = 1) = app.mjolnir.client.LocalUpload(path, rel, "x/y", size)

    @Test fun summaryCountsOnlySelectedFiles() {
        val mb = 1024L * 1024
        val s = SendState().copy(files = listOf(upload("/x/a.txt", "a.txt", 3 * mb), upload("/x/b.txt", "b.txt", 2 * mb)), selected = setOf("/x/a.txt"))
        assertEquals("1 of 2 selected · 3 MB", s.summary)
        assertEquals(listOf(s.files[0]), s.selectedFiles)
    }

    @Test fun sentPrefixLinkKeepsLongerUnchecked() {
        val s = SendState().withText("https://a.com https://a.com/page").toggle("https://a.com/page")
        val after = s.withoutLinks(setOf("https://a.com"))
        assertEquals(listOf("https://a.com/page"), after.links)
        assertTrue(after.selected.isEmpty())
        assertEquals("https://a.com/page", after.text)
    }

    @Test fun sendKeepsProseAroundUnsentLinks() {
        val s = SendState().withText("look https://a.com/x and note\nsecond line https://b.com").toggle("https://a.com/x")
        val after = s.withoutLinks(setOf("https://b.com"))
        assertEquals("look https://a.com/x and note\nsecond line", after.text)
        assertEquals(listOf("https://a.com/x"), after.links)
        assertTrue(after.selected.isEmpty())
    }

    @Test fun sendRemovesOnlySentTokenFromMixedLine() {
        val after = SendState().withText("see https://a.com and https://a.com/page").withoutLinks(setOf("https://a.com"))
        assertEquals("see and https://a.com/page", after.text)
        assertEquals(listOf("https://a.com/page"), after.links)
    }

    @Test fun sentLinkWithBalancedParensRemovedFromText() {
        val u = "https://en.wikipedia.org/wiki/Foo_(bar)"
        val s = SendState().withText("read $u. ok")
        assertEquals(listOf(u), s.links)
        val after = s.withoutLinks(setOf(u))
        assertEquals("read ok", after.text)
        assertTrue(after.links.isEmpty() && extractUrls(after.text).isEmpty())
    }

    @Test fun sentQuotedOrBracketedLinkRemovedFromText() {
        for ((open, close) in listOf("\"" to "\"", "<" to ">", "[" to "]")) {
            val s = SendState().withText("go ${open}https://a.com/x$close now")
            val after = s.withoutLinks(setOf("https://a.com/x"))
            assertEquals("go now", after.text, open)
            assertTrue(extractUrls(after.text).isEmpty(), open)
        }
    }

    @Test fun untouchedLinesKeepWhitespace() {
        val text = "  indented   note\n\nhttps://a.com\nkeep  https://b.com/y  "
        val after = SendState().withText(text).withoutLinks(setOf("https://a.com"))
        assertEquals("  indented   note\n\nkeep  https://b.com/y  ", after.text)
    }

    @Test fun uncheckedFileMeansCannotSend() {
        val peer = Peer("p", "P", DeviceKind.PC)
        val s = SendState().withTargets(listOf(peer), null).copy(files = listOf(upload("/x/a.txt", "a.txt")))
        assertFalse(s.canSend)
        assertTrue(s.toggle("/x/a.txt").canSend)
    }

    @Test fun selectRowSetsPreviewKey() {
        assertEquals("https://a.com", SendState().selectRow("https://a.com").selectedRow)
    }

    @Test fun groupsFollowTargetsAndLastUsed() {
        val a = Peer("a", "Alpha", DeviceKind.PC); val b = Peer("b", "Beta", DeviceKind.PHONE)
        val s = SendState().withTargets(listOf(a, b), "b")
        assertEquals("b", s.lastUsedId)
        assertEquals(listOf(b, a), s.groups.single().peers)
    }

    @Test fun removingTheSelectedRowClearsIt() {
        val s = SendState().copy(files = listOf(upload("/x/a.txt", "a.txt"), upload("/x/b.txt", "b.txt"))).selectRow("/x/a.txt")
        assertNull(s.removeFile("/x/a.txt").selectedRow)
        assertEquals("/x/a.txt", s.removeFile("/x/b.txt").selectedRow)
    }

    @Test fun sendingTheSelectedLinkClearsItsRow() {
        val s = SendState().withText("https://a.com https://b.com").selectRow("https://a.com")
        assertNull(s.withoutLinks(setOf("https://a.com")).selectedRow)
        assertEquals("https://a.com", s.withoutLinks(setOf("https://b.com")).selectedRow)
    }

    @Test fun restartSelectsTheSameCardAsLastTime() {
        val paired = Peer("f", "Fred", DeviceKind.PC)
        val inWork = Peer("f", "Fred", DeviceKind.PC, SpaceRef("s2", "Work"))
        val s = SendState().withTargets(listOf(paired, inWork), "f", "s2")
        assertEquals(DestinationKey("f", "s2"), s.targetKey)
        assertEquals(DestinationKey("f", null), SendState().withTargets(listOf(paired, inWork), "f", null).targetKey)
        // the remembered space is gone: fall back to the device's paired card
        assertEquals(DestinationKey("f", null), SendState().withTargets(listOf(paired, inWork), "f", "gone").targetKey)
    }

    @Test fun lastTargetPeerPrefersTheRememberedCard() {
        val paired = Peer("f", "Fred", DeviceKind.PC)
        val inWork = Peer("f", "Fred", DeviceKind.PC, SpaceRef("s2", "Work"))
        assertEquals(inWork, lastTargetPeer(listOf(paired, inWork), "f", "s2"))
        assertEquals(paired, lastTargetPeer(listOf(inWork, paired), "f", null))
        assertEquals(paired, lastTargetPeer(listOf(inWork, paired), "f", "gone"))
        assertEquals(inWork, lastTargetPeer(listOf(inWork), "f", null)) // only a space card exists
        assertNull(lastTargetPeer(emptyList(), "f", null))
    }
}
