package app.mjolnir.desktop.ui

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

class ThumbnailsTest {
    private val dir: File = Files.createTempDirectory("thumbs").toFile().apply { deleteOnExit() }

    private fun png(w: Int, h: Int): File = File(dir, "p${w}x$h.png").also {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = Color.CYAN; fillRect(0, 0, w, h); dispose() }
        ImageIO.write(img, "png", it)
    }

    @Test fun subsamplingKeepsTheDecodeNearTheTarget() {
        assertEquals(1, subsampleStep(500, 300, 320)) // already small: decoded as is
        assertEquals(3, subsampleStep(1000, 600, 320)) // long side 333: between 1× and 2× the target
        assertEquals(25, subsampleStep(8000, 6000, 320)) // a 48 MP photo decodes at 320 × 240, not in full
        assertEquals(3, subsampleStep(8000, 6000, 2560)) // the viewer on a 2560 px screen: 2666 × 2000, not 48 MP
    }

    @Test fun viewerDecodeIsNotKeptInTheCache() {
        val f = png(900, 600)
        val big = assertNotNull(decodeImage(f, 600, cached = false))
        assertEquals(600, big.width); assertEquals(400, big.height)
        assertFalse(thumbnailCached(f.path))
        assertNotNull(decodeImage(f, 300)) // an inline one is cached
        assertTrue(thumbnailCached(f.path))
    }

    @Test fun skiaFallbackIsScaledToTheTarget() {
        // WebP: ImageIO has no reader, so Skia decodes it; no width/height given, the header's are used.
        val webp = File(dir, "photo.webp").apply {
            val surface = Surface.makeRasterN32Premul(800, 600)
            surface.canvas.clear(0xFF3366CC.toInt())
            writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.WEBP, 80)!!.bytes)
        }
        assertNull(loadThumbnail(webp.path, 100)) // really the Skia path
        val img = assertNotNull(decodeImage(webp, 100))
        assertEquals(100, img.width); assertEquals(75, img.height)
        assertEquals(800, assertNotNull(decodeWithSkia(webp, 2000)).width) // never enlarged
    }
}
