package app.mjolnir.desktop

import app.mjolnir.client.*
import app.mjolnir.protocol.*
import java.io.File
import java.net.URI
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.io.TempDir

class DesktopCatcherTest {
    private val phone = Peer("ph", "Pixel 8", DeviceKind.PHONE)
    private val opened = mutableListOf<String>()
    private val notes = mutableListOf<String>()
    private val marked = mutableListOf<String>()
    private val opener = TabOpener(browse = { opened += it.toString() }, pause = {})
    private var openLinks = true
    private var nameOf: (Peer) -> String = { it.name }
    private lateinit var history: ReceivedHistory
    private fun catcher(api: FakeRelayApi, dir: File): DesktopCatcher {
        history = ReceivedHistory(JsonFileStore(File(dir.parentFile, "hist-${dir.name}.json"), ListSerializer(ReceivedEntry.serializer())) { emptyList() })
        return DesktopCatcher(api, opener, { dir }, { notes += it }, history, { openLinks }, { nameOf(it) }, { marked += it.name })
    }
    private fun d(tabs: List<Tab>, files: List<FileRef>) = Delivery("d1", phone, tabs, 0, files)

    // files land first, then tabs open, then one notification
    @Test fun savesFilesThenOpensTabsAndNotifies(@TempDir dir: File) = runTest {
        val api = FakeRelayApi(); val ref = api.putBlob("f1", "report.pdf", "pdf".toByteArray())
        catcher(api, dir).handle(d(listOf(Tab("https://a.com")), listOf(ref)))
        assertEquals("pdf", File(dir, "report.pdf").readText())
        assertEquals(listOf("https://a.com"), opened); assertEquals(listOf("report.pdf"), marked)
        assertEquals(listOf("Caught 1 tab and 1 file from Pixel 8"), notes)
    }
    // an existing file is kept; the new one gets a counter
    @Test fun existingFileIsNotOverwritten(@TempDir dir: File) = runTest {
        File(dir, "report.pdf").writeText("old")
        val api = FakeRelayApi(); val ref = api.putBlob("f1", "report.pdf", "new".toByteArray())
        catcher(api, dir).handle(d(emptyList(), listOf(ref)))
        assertEquals("old", File(dir, "report.pdf").readText()); assertEquals("new", File(dir, "report (1).pdf").readText())
    }
    // a missing blob fails the whole delivery with nothing published
    @Test fun failedDownloadSavesNothingAndOpensNothing(@TempDir dir: File) = runTest {
        val api = FakeRelayApi(); val ref = api.putBlob("f1", "a.txt", "x".toByteArray()); api.blobs.clear()
        val e = assertFailsWith<RelayException> { catcher(api, dir).handle(d(listOf(Tab("https://a.com")), listOf(ref))) }
        assertEquals(RelayErrorKind.NOT_FOUND, e.kind)
        assertTrue(dir.walkTopDown().none { it.isFile })
        assertTrue(opened.isEmpty()); assertTrue(notes.isEmpty()); assertTrue(marked.isEmpty())
    }
    // a received folder gets one unique top-level name
    @Test fun folderArrivesUnderOneUniqueFolder(@TempDir dir: File) = runTest {
        File(dir, "Photos").mkdirs()
        val api = FakeRelayApi()
        val refs = listOf(api.putBlob("a", "Photos/a.jpg", "1".toByteArray()), api.putBlob("b", "Photos/2024/b.jpg", "2".toByteArray()))
        catcher(api, dir).handle(d(emptyList(), refs))
        assertEquals("1", File(dir, "Photos (1)/a.jpg").readText()); assertEquals("2", File(dir, "Photos (1)/2024/b.jpg").readText())
    }
    // a hostile ref name cannot leave the save folder: only the safe relative path is used
    @Test fun hostileNameStaysInsideSaveDir(@TempDir dir: File) = runTest {
        val api = FakeRelayApi()
        val ref = api.putBlob("f", "evil.bat", "x".toByteArray()).copy(name = "..\\..\\Start Menu\\Programs\\Startup\\evil.bat")
        catcher(api, dir).handle(d(emptyList(), listOf(ref)))
        assertEquals(listOf("evil.bat"), dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toList())
    }
    // a hostile relative path is flattened of ".." and stays under the save folder
    @Test fun hostileRelativePathStaysInsideSaveDir(@TempDir dir: File) = runTest {
        val api = FakeRelayApi()
        val ref = api.putBlob("f", "..\\..\\Start Menu\\Programs\\Startup\\evil.bat", "x".toByteArray())
        catcher(api, dir).handle(d(emptyList(), listOf(ref)))
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, files.size)
        assertTrue(files.single().canonicalPath.startsWith(dir.canonicalPath + File.separator))
        assertEquals("evil.bat", files.single().name)
    }
    // R15: a failing notification does not fail the delivery (which would save it again on redelivery)
    @Test fun notifyFailureDoesNotFailHandle(@TempDir dir: File) = runTest {
        val api = FakeRelayApi(); val ref = api.putBlob("f1", "a.txt", "x".toByteArray())
        catcher(api, dir).also { notes.clear() }.let { DesktopCatcher(api, opener, { dir }, { error("tray gone") }, history, { true }, { it.name }, {}) }.handle(d(emptyList(), listOf(ref)))
        assertEquals("x", File(dir, "a.txt").readText())
    }

    @Test fun recordsLinksAndFolders(@TempDir dir: File) = runTest {
        val api = FakeRelayApi()
        val refs = listOf(api.putBlob("a", "Holiday/a.jpg", "1".toByteArray()), api.putBlob("b", "Holiday/b.jpg", "2".toByteArray()))
        val c = catcher(api, dir)
        c.handle(d(listOf(Tab("https://a.com", "A"), Tab("https://b.com")), refs))
        val e = history.entries.value
        assertEquals(3, e.size)
        assertEquals(listOf("https://a.com", "https://b.com"), e.filter { it.kind == Kind.LINK }.map { it.target })
        assertEquals(listOf("A", "https://b.com"), e.filter { it.kind == Kind.LINK }.map { it.title })
        val folder = e.single { it.kind == Kind.FOLDER }
        assertEquals("Holiday", folder.title); assertEquals(File(dir, "Holiday").absolutePath, folder.target)
        assertEquals(listOf("d1:0", "d1:1", "d1:2"), e.map { it.id }.sorted())
        assertTrue(e.all { it.fromDeviceId == "ph" && it.fromName == "Pixel 8" })
    }

    @Test fun recordsPlainFile(@TempDir dir: File) = runTest {
        val api = FakeRelayApi(); val ref = api.putBlob("f1", "report.pdf", "pdf".toByteArray())
        catcher(api, dir).handle(d(emptyList(), listOf(ref)))
        val e = history.entries.value.single()
        assertEquals(Kind.FILE, e.kind); assertEquals("report.pdf", e.title); assertEquals(File(dir, "report.pdf").absolutePath, e.target)
    }

    @Test fun doesNotOpenLinksWhenSettingOff(@TempDir dir: File) = runTest {
        openLinks = false
        val c = catcher(api = FakeRelayApi(), dir = dir)
        c.handle(d(listOf(Tab("https://a.com")), emptyList()))
        assertTrue(opened.isEmpty())
        assertEquals(1, history.entries.value.size)
    }

    @Test fun notificationUsesNickname(@TempDir dir: File) = runTest {
        nameOf = { "Sam's phone" }
        catcher(FakeRelayApi(), dir).handle(d(listOf(Tab("https://a.com")), emptyList()))
        assertEquals(listOf("Caught 1 tab from Sam's phone"), notes)
    }
}
