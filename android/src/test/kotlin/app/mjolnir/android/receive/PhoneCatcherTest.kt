package app.mjolnir.android.receive

import app.mjolnir.android.data.CaughtItem
import app.mjolnir.android.data.SavedFile
import app.mjolnir.android.send.NetDecision
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.ReceivedPath
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Tab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Test

class PhoneCatcherTest {
    private val dir: File = createTempDirectory("catcher").toFile()
    private val api = FakeRelayApi()
    private val store = JsonFileStore(File(dir, "caught.json"), ListSerializer(CaughtItem.serializer())) { emptyList() }
    private val staging = File(dir, "incoming")
    private val publisher = RecordingPublisher()
    private val notes = mutableListOf<String>()
    private val pc = Peer("pc1", "DESKTOP-4F2K", DeviceKind.PC)

    private fun catcher(decide: (Delivery) -> NetDecision = { NetDecision.GO }) =
        PhoneCatcher(api, store, staging, publisher, notify = { notes += it }, decide = decide, now = { 1_000 })

    private val c = catcher()

    @After fun cleanup() { dir.deleteRecursively() }

    @Test fun storesSafeTabsAndNotifies() = runTest {
        c.handle(Delivery("d1", pc, listOf(Tab("https://a.com", "A"), Tab("https://b.com")), 0))
        assertEquals(listOf("https://a.com", "https://b.com"), store.read().mapNotNull { it.tab?.url })
        assertEquals(listOf("DESKTOP-4F2K", "DESKTOP-4F2K"), store.read().map { it.fromName })
        assertEquals(listOf(1_000L, 1_000L), store.read().map { it.caughtAtEpochMs })
        assertEquals(listOf("Caught 2 tabs from DESKTOP-4F2K"), notes)
    }

    @Test fun savesLooseFileAndNotifies() = runTest {
        val ref = api.putBlob("f1", "report.pdf", byteArrayOf(1, 2))
        c.handle(Delivery("d1", Peer("pc1", "DESKTOP-4F2K", DeviceKind.PC), emptyList(), 0, listOf(ref)))
        assertEquals(listOf(ReceivedPath(null, "report.pdf")), publisher.targets)
        assertEquals(listOf("report.pdf"), store.read().mapNotNull { it.file?.name })
        assertEquals(listOf("Caught 1 file from DESKTOP-4F2K"), notes)
        assertEquals(listOf(listOf<Byte>(1, 2)), publisher.bytes)
        assertEquals(2L, store.read().single().file?.sizeBytes)
    }

    @Test fun folderBecomesOneCaughtEntry() = runTest {
        val a = api.putBlob("f1", "Photos/a.jpg", byteArrayOf(1, 2, 3))
        val b = api.putBlob("f2", "Photos/2024/b.jpg", byteArrayOf(4))
        publisher.existing += "Photos"
        c.handle(Delivery("d1", pc, emptyList(), 0, listOf(a, b)))
        assertEquals(listOf(ReceivedPath("Photos (1)", "a.jpg"), ReceivedPath("Photos (1)/2024", "b.jpg")), publisher.targets)
        val items = store.read()
        assertEquals(1, items.size)
        assertEquals("Photos (1)", items[0].folder?.name)
        assertEquals(2, items[0].folder?.fileCount)
        assertEquals(4L, items[0].folder?.totalBytes)
        assertEquals(listOf("Caught 2 files from DESKTOP-4F2K"), notes)
    }

    @Test fun largeDeliveryOnMobileDataWaits() = runTest {
        val c = catcher { NetDecision.WAIT_FOR_WIFI }
        val d = Delivery("d1", pc, emptyList(), 0, listOf(api.putBlob("f1", "big.bin", ByteArray(10))))
        assertFailsWith<WaitingForWifi> { c.handle(d) }
        assertEquals(listOf(d), c.waiting)
        assertTrue(store.read().isEmpty())
        assertTrue(publisher.targets.isEmpty())
        assertTrue(notes.isEmpty())
        c.resetWaiting()
        assertTrue(c.waiting.isEmpty())
    }

    @Test fun tabsOnlyDeliveryNeverWaits() = runTest {
        val c = catcher { NetDecision.WAIT_FOR_WIFI }
        c.handle(Delivery("d1", pc, listOf(Tab("https://a.com")), 0))
        assertEquals(listOf("https://a.com"), store.read().mapNotNull { it.tab?.url })
        assertTrue(c.waiting.isEmpty())
    }

    @Test fun failedDownloadStoresNothing() = runTest {
        val missing = FileRef("nope", "x.pdf", 2, "application/pdf", "00", "x.pdf")
        assertFailsWith<RelayException> { c.handle(Delivery("d1", pc, emptyList(), 0, listOf(missing))) }
        assertTrue(store.read().isEmpty())
        assertTrue(publisher.targets.isEmpty())
        assertTrue(notes.isEmpty())
    }

    @Test fun hostileFileNameIsSanitized() = runTest {
        val ref = api.putBlob("f1", "../../x/CON", byteArrayOf(9))
        c.handle(Delivery("d1", pc, emptyList(), 0, listOf(ref)))
        assertEquals(listOf(ReceivedPath("x", "_CON")), publisher.targets)
    }

    @Test fun unsafeTabsNeverStored() = runTest {
        val tabs = listOf(Tab("javascript:alert(1)"), Tab("file:///sdcard/x"), Tab("intent://x#Intent;end"), Tab("https://ok.com"))
        c.handle(Delivery("d1", pc, tabs, 0))
        assertEquals(listOf("https://ok.com"), store.read().mapNotNull { it.tab?.url })
        assertEquals(listOf("Caught 1 tab from DESKTOP-4F2K"), notes)
    }

    @Test fun keepsNewest200() = runTest {
        store.write(List(199) { CaughtItem(tab = Tab("https://old.com/$it"), fromName = "PC", caughtAtEpochMs = 0) })
        c.handle(Delivery("d1", pc, List(5) { Tab("https://new.com/$it") }, 0))
        val items = store.read()
        assertEquals(200, items.size)
        assertEquals(List(5) { "https://new.com/$it" }, items.take(5).map { it.tab?.url })
        assertEquals("https://old.com/0", items[5].tab?.url)
    }

    @Test fun nothingCaughtNoNotification() = runTest {
        c.handle(Delivery("d1", pc, listOf(Tab("javascript:x")), 0))
        assertTrue(store.read().isEmpty())
        assertTrue(notes.isEmpty())
    }

    @Test fun failingNotifyDoesNotFailTheDelivery() = runTest {
        val c = PhoneCatcher(api, store, staging, publisher, notify = { error("no notifications") }, decide = { NetDecision.GO })
        c.handle(Delivery("d1", pc, listOf(Tab("https://a.com")), 0))
        assertEquals(1, store.read().size)
    }

    private class RecordingPublisher : FilePublisher {
        val targets = mutableListOf<ReceivedPath>()
        val bytes = mutableListOf<List<Byte>>()
        val existing = mutableSetOf<String>()

        override fun publish(part: File, target: ReceivedPath, ref: FileRef): SavedFile {
            targets += target
            val data = part.readBytes()
            bytes += data.toList()
            return SavedFile(target.fileName, "content://downloads/${targets.size}", data.size.toLong(), ref.mimeType)
        }

        override fun folderExists(name: String): Boolean = name in existing
    }
}
