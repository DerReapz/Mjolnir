package app.mjolnir.android.share

import app.mjolnir.android.data.TrayItem
import app.mjolnir.protocol.Limits
import java.io.File
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Endless-looking stream of [size] zero bytes that never allocates them all. */
private class ZeroStream(private var remaining: Long) : InputStream() {
    override fun read(): Int = if (remaining-- > 0) 0 else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = minOf(len.toLong(), remaining).toInt()
        b.fill(0, off, off + n); remaining -= n; return n
    }
}

class FileImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val outbox: File get() = File(temp.root, "outbox")

    private fun entry(path: String, size: Long = 10) = FolderEntry(path, "content://tree/$path", size, "image/jpeg")

    @Test fun copiesIntoOutboxWithSafeName() {
        val bytes = byteArrayOf(1, 2, 3)
        val result = assertIs<ImportResult.Ok>(importFile("../x/photo.jpg", 3, "image/jpeg", { bytes.inputStream() }, outbox))
        val item = assertIs<TrayItem.LocalFile>(result.item)
        assertEquals("photo.jpg", item.name)
        assertEquals(3, item.sizeBytes)
        assertEquals("image/jpeg", item.mimeType)
        val copy = File(item.path)
        assertEquals(outbox.canonicalFile, copy.canonicalFile.parentFile)
        assertTrue(bytes.contentEquals(copy.readBytes()))
        assertNull(result.notice)
    }

    @Test fun knownOversizeIsRejectedWithoutReading() = assertEquals(
        ImportResult.Rejected("photo.jpg is too big (max 100 MB)"),
        importFile("photo.jpg", Limits.MAX_FILE_BYTES + 1, null, { error("must not read") }, outbox),
    )

    @Test fun unknownSizeOverLimitIsRejectedAndCleanedUp() {
        assertEquals(
            ImportResult.Rejected("photo.jpg is too big (max 100 MB)"),
            importFile("photo.jpg", null, null, { ZeroStream(Limits.MAX_FILE_BYTES + 1) }, outbox),
        )
        assertTrue(outbox.listFiles().orEmpty().isEmpty())
    }

    @Test fun unreadableStreamIsRejected() =
        assertEquals(ImportResult.Rejected("Couldn't read photo.jpg"), importFile("photo.jpg", 3, null, { null }, outbox))

    @Test fun mimeTypeDefaultsToOctetStream() {
        val result = assertIs<ImportResult.Ok>(importFile(null, null, null, { byteArrayOf(1).inputStream() }, outbox))
        val item = assertIs<TrayItem.LocalFile>(result.item)
        assertEquals("file", item.name)
        assertEquals("application/octet-stream", item.mimeType)
    }

    @Test fun folderBecomesOneTrayItem() {
        val result = assertIs<ImportResult.Ok>(importFolder("Photos", listOf(entry("Photos/a.jpg"), entry("Photos/2024/b.jpg"), entry("Photos/c.jpg"))))
        val folder = assertIs<TrayItem.Folder>(result.item)
        assertEquals("Photos", folder.name)
        assertEquals(listOf("Photos/a.jpg", "Photos/2024/b.jpg", "Photos/c.jpg"), folder.files.map { it.relativePath })
        assertEquals("content://tree/Photos/a.jpg", folder.files[0].path)
        assertNull(result.notice)
    }

    @Test fun oversizeFilesInFolderAreLeftOut() {
        val result = assertIs<ImportResult.Ok>(importFolder("Photos",
            listOf(entry("Photos/a.jpg"), entry("Photos/big.mov", Limits.MAX_FILE_BYTES + 1), entry("Photos/c.jpg"))))
        assertEquals(2, assertIs<TrayItem.Folder>(result.item).files.size)
        assertEquals("1 file over 100 MB was left out", result.notice)
    }

    @Test fun folderOver1000FilesIsRejected() = assertEquals(
        ImportResult.Rejected("Photos has too many files (max 1000)"),
        importFolder("Photos", (1..1001).map { entry("Photos/$it.jpg") }),
    )

    @Test fun emptyFolderIsRejected() = assertEquals(ImportResult.Rejected("Photos has no files"), importFolder("Photos", emptyList()))

    @Test fun unsafeRelativePathsAreCleaned() {
        val folder = assertIs<TrayItem.Folder>(assertIs<ImportResult.Ok>(importFolder("Photos", listOf(entry("Photos/../a?.jpg")))).item)
        assertEquals("Photos/a_.jpg", folder.files.single().relativePath)
    }
}
