package app.mjolnir.desktop

import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class ItemPreviewTest {
    @TempDir lateinit var dir: File

    @Test fun photoReadsDimensionsFromHeader() {
        val png = File(dir, "dot.png")
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png)
        val p = assertIs<ItemPreview.Photo>(previewOf(png))
        assertEquals(1, p.width); assertEquals(1, p.height)
        assertEquals("dot.png", p.name); assertEquals(png.length(), p.sizeBytes); assertEquals(png.path, p.path)
    }

    @Test fun pdfIsOtherFileWithTypeLabel() {
        val pdf = File(dir, "a.pdf").apply { writeBytes(ByteArray(10)) }
        assertEquals(ItemPreview.OtherFile("a.pdf", 10, "PDF", pdf.path), previewOf(pdf))
        val bare = File(dir, "README").apply { writeText("x") }
        assertEquals("File", assertIs<ItemPreview.OtherFile>(previewOf(bare)).typeLabel)
    }

    @Test fun folderCountsChildren() {
        val root = File(dir, "Photos").apply { mkdir() }
        File(root, "a.txt").writeBytes(ByteArray(5))
        File(root, "sub").apply { mkdir() }.also { File(it, "b.txt").writeBytes(ByteArray(7)); File(it, "c.txt").writeBytes(ByteArray(8)) }
        val f = assertIs<ItemPreview.Folder>(previewOf(root))
        assertEquals(3, f.files); assertEquals(20, f.sizeBytes)
        assertEquals(
            listOf(ItemPreview.FolderChild("sub", true, 2, 15), ItemPreview.FolderChild("a.txt", false, 1, 5)),
            f.children,
        )
    }

    @Test fun previewOfMissingFileFallsBack() =
        assertEquals(ItemPreview.Missing("gone.txt"), previewOf(File(dir, "gone.txt")))

    @Test fun detailLinesMatchSpec() {
        val mb = 1024L * 1024
        assertEquals("Photo · 4 MB · 4032 × 3024", detailLine(ItemPreview.Photo("a.jpg", 4 * mb, 4032, 3024, "p")))
        assertEquals("PDF · 180 KB", detailLine(ItemPreview.OtherFile("a.pdf", 180 * 1024, "PDF", "p")))
        assertEquals("Folder · 212 files · 1.4 GB", detailLine(ItemPreview.Folder("F", 212, (1.4 * 1024 * mb).toLong(), emptyList(), "p")))
        assertEquals("Link · en.wikipedia.org/wiki/Mjölnir", detailLine(previewOfLink("https://en.wikipedia.org/wiki/Mj%C3%B6lnir", null)))
        assertEquals("Missing", detailLine(ItemPreview.Missing("x")))
    }
}
