package app.mjolnir.client

import app.mjolnir.protocol.AttachmentKind
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaInfoTest {
    private val dir = createTempDirectory("media-info").toFile()

    @Test fun classifiesByMime() {
        assertEquals(AttachmentKind.GIF, classify("image/gif", "a.bin"))
        for (mime in listOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/bmp")) {
            assertEquals(AttachmentKind.IMAGE, classify(mime, "a.bin"), mime)
        }
        assertEquals(AttachmentKind.IMAGE, classify("IMAGE/JPEG; charset=binary", "a.bin"))
        assertEquals(AttachmentKind.VIDEO, classify("video/mp4", "a.bin"))
        assertEquals(AttachmentKind.VIDEO, classify("video/quicktime", "a.bin"))
        assertEquals(AttachmentKind.FILE, classify("image/svg+xml", "a.svg"))
        assertEquals(AttachmentKind.FILE, classify("application/pdf", "a.pdf"))
        // No (or a generic) type: the name decides.
        assertEquals(AttachmentKind.IMAGE, classify(null, "Photo.JPG"))
        assertEquals(AttachmentKind.GIF, classify("application/octet-stream", "funny.gif"))
        assertEquals(AttachmentKind.VIDEO, classify(null, "clip.mov"))
        assertEquals(AttachmentKind.FILE, classify(null, "notes.txt"))
        assertEquals(AttachmentKind.FILE, classify(null, "README"))
    }

    @Test fun corruptImageBecomesFile() {
        val garbage = File(dir, "broken.png").apply { writeBytes(ByteArray(500) { (it * 7).toByte() }) }
        val truncated = File(dir, "cut.png").apply { writeBytes(png(BufferedImage(50, 40, BufferedImage.TYPE_INT_RGB)).copyOf(60)) }
        for (f in listOf(garbage, truncated)) {
            assertNull(ImageIoThumbnailer.thumbnail(f, AttachmentKind.IMAGE), f.name)
            val info = mediaInfo(f, "image/png", ImageIoThumbnailer)
            assertEquals(AttachmentKind.FILE, info.kind, f.name); assertNull(info.thumb)
        }
        // A thumbnailer that throws is a corrupt image too.
        val info = mediaInfo(garbage, "image/png") { _, _ -> error("decoder crashed") }
        assertEquals(AttachmentKind.FILE, info.kind)
    }

    @Test fun hugeImageThumbnailWithin320() {
        // 12000×9000 at one bit per pixel: 13.5 MB to build, about 400 MB if decoded whole as RGB.
        val big = BufferedImage(12000, 9000, BufferedImage.TYPE_BYTE_BINARY)
        big.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 6000, 9000); dispose() }
        val file = File(dir, "huge.png").apply { writeBytes(png(big)) }
        val info = mediaInfo(file, "image/png", ImageIoThumbnailer)
        assertEquals(AttachmentKind.IMAGE, info.kind)
        val thumb = assertNotNull(info.thumb)
        assertEquals(12000 to 9000, thumb.width to thumb.height) // the original's size, for the bubble's shape
        assertNull(thumb.durationMs)
        val jpeg = assertNotNull(ImageIO.read(ByteArrayInputStream(thumb.jpeg)), "a readable JPEG")
        assertEquals(320 to 240, jpeg.width to jpeg.height)
        assertTrue(thumb.jpeg.size > 2 && thumb.jpeg[0] == 0xFF.toByte() && thumb.jpeg[1] == 0xD8.toByte(), "JPEG magic")
        // Left half white, right half black survives the scaling.
        assertTrue(Color(jpeg.getRGB(40, 120)).red > 200); assertTrue(Color(jpeg.getRGB(280, 120)).red < 60)
    }

    @Test fun smallImageIsNotEnlarged() {
        val file = File(dir, "small.png").apply { writeBytes(png(BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB))) }
        val thumb = assertNotNull(ImageIoThumbnailer.thumbnail(file, AttachmentKind.IMAGE))
        val jpeg = ImageIO.read(ByteArrayInputStream(thumb.jpeg))
        assertEquals(100 to 50, jpeg.width to jpeg.height); assertEquals(100 to 50, thumb.width to thumb.height)
    }

    @Test fun gifThumbnailIsFirstFrame() {
        val file = File(dir, "anim.gif").apply { writeBytes(gif(listOf(solid(Color.RED), solid(Color.BLUE)))) }
        val info = mediaInfo(file, "image/gif", ImageIoThumbnailer)
        assertEquals(AttachmentKind.GIF, info.kind)
        val jpeg = ImageIO.read(ByteArrayInputStream(assertNotNull(info.thumb).jpeg))
        val c = Color(jpeg.getRGB(jpeg.width / 2, jpeg.height / 2))
        assertTrue(c.red > 200 && c.blue < 60, "first frame is red, got $c")
    }

    @Test fun videoAndFilesGetNoThumbnail() {
        val file = File(dir, "clip.mp4").apply { writeBytes(ByteArray(100)) }
        assertNull(ImageIoThumbnailer.thumbnail(file, AttachmentKind.VIDEO))
        val info = mediaInfo(file, "video/mp4", ImageIoThumbnailer)
        assertEquals(AttachmentKind.VIDEO, info.kind); assertNull(info.thumb) // stays a video until the video plan
        assertEquals(AttachmentKind.FILE, mediaInfo(File(dir, "x.txt").apply { writeText("x") }, "text/plain", ImageIoThumbnailer).kind)
    }

    private fun png(img: BufferedImage): ByteArray = java.io.ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    private fun solid(c: Color) = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply { color = c; fillRect(0, 0, 64, 48); dispose() }
    }

    private fun gif(frames: List<BufferedImage>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.prepareWriteSequence(null)
            for (f in frames) {
                val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(f), null)
                val root = meta.getAsTree(meta.nativeMetadataFormatName) as IIOMetadataNode
                val gce = IIOMetadataNode("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none"); setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE"); setAttribute("delayTime", "10"); setAttribute("transparentColorIndex", "0")
                }
                root.appendChild(gce)
                meta.setFromTree(meta.nativeMetadataFormatName, root)
                writer.writeToSequence(IIOImage(f, null, meta), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
        return out.toByteArray()
    }
}
