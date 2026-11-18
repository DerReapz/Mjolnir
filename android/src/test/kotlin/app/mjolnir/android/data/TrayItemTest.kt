package app.mjolnir.android.data

import app.mjolnir.client.LocalUpload
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.protocol.MjolnirJson
import app.mjolnir.protocol.Tab
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test

class TrayItemTest {
    private fun upload(rel: String) = LocalUpload("/outbox/$rel", rel, "text/plain", 5, uploadId = "up", ref = null)

    @Test fun returnToTrayRegroupsFolders() {
        val link = TrayItem.Link(Tab("https://a.com"))
        val item = OutgoingFiles("o1", "pc1", "PC", listOf(upload("f.txt"), upload("Photos/a.jpg"), upload("Photos/b.jpg")), createdAtEpochMs = 0)
        val tray = returnToTray(listOf(link), item)
        assertEquals(3, tray.size)
        assertEquals(link, tray[0])
        val file = assertIs<TrayItem.LocalFile>(tray[1])
        assertEquals("f.txt", file.name); assertEquals("/outbox/f.txt", file.path); assertEquals(5, file.sizeBytes)
        val folder = assertIs<TrayItem.Folder>(tray[2])
        assertEquals("Photos", folder.name)
        assertEquals(listOf("Photos/a.jpg", "Photos/b.jpg"), folder.files.map { it.relativePath })
        assertNull(folder.files[0].uploadId)
    }

    @Test fun toUploadsUsesNameForAFileAndFilesForAFolder() {
        val f = TrayItem.LocalFile("id1", "/outbox/id1", "f.txt", 5, "text/plain")
        assertEquals(listOf(LocalUpload("/outbox/id1", "f.txt", "text/plain", 5)), f.toUploads())
        val folder = TrayItem.Folder("id2", "Photos", listOf(upload("Photos/a.jpg")))
        assertEquals(folder.files, folder.toUploads())
        assertEquals(emptyList(), TrayItem.Link(Tab("https://a.com")).toUploads())
    }

    @Test fun trayRoundTripsThroughJson() {
        val tray = listOf(
            TrayItem.Link(Tab("https://a.com", "A")),
            TrayItem.LocalFile("id1", "/outbox/id1", "f.txt", 5, "text/plain"),
            TrayItem.Folder("id2", "Photos", listOf(upload("Photos/a.jpg"))),
        )
        val serializer = ListSerializer(TrayItem.serializer())
        assertEquals(tray, MjolnirJson.decodeFromString(serializer, MjolnirJson.encodeToString(serializer, tray)))
        assertEquals("https://a.com", tray[0].id)
    }
}
