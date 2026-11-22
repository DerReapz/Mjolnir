package app.mjolnir.desktop.ui

import androidx.compose.ui.graphics.toPixelMap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A GIF of solid [colors] frames, each shown for its [delaysCs] (hundredths of a second), looping forever unless [loops] is set. */
internal fun gifOf(colors: List<Color>, delaysCs: List<Int>, w: Int = 8, h: Int = 6, loops: Int? = 0): ByteArray {
    val out = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("gif").next()
    ImageIO.createImageOutputStream(out).use { ios ->
        writer.output = ios
        writer.prepareWriteSequence(null)
        colors.forEachIndexed { i, color ->
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply { this.color = color; fillRect(0, 0, w, h); dispose() }
            val param = writer.defaultWriteParam
            val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param)
            val format = meta.nativeMetadataFormatName
            val root = meta.getAsTree(format) as IIOMetadataNode
            fun child(name: String) = (0 until root.length).map { root.item(it) as IIOMetadataNode }.firstOrNull { it.nodeName == name }
                ?: IIOMetadataNode(name).also(root::appendChild)
            child("GraphicControlExtension").apply {
                setAttribute("disposalMethod", "none"); setAttribute("userInputFlag", "FALSE")
                setAttribute("transparentColorFlag", "FALSE"); setAttribute("transparentColorIndex", "0")
                setAttribute("delayTime", delaysCs[i].toString())
            }
            if (i == 0 && loops != null) {
                val app = IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE"); setAttribute("authenticationCode", "2.0")
                    userObject = byteArrayOf(1, (loops and 0xFF).toByte(), ((loops shr 8) and 0xFF).toByte())
                }
                child("ApplicationExtensions").appendChild(app)
            }
            meta.setFromTree(format, root)
            writer.writeToSequence(IIOImage(img, null, meta), param)
        }
        writer.endWriteSequence()
    }
    writer.dispose()
    return out.toByteArray()
}

/** A valid little GIF whose logical screen (the canvas Skia allocates per frame) claims 65535 × 65535. */
internal fun hugeCanvasGif(): ByteArray = gifOf(listOf(Color.RED, Color.GREEN), listOf(10, 10)).also {
    for (i in 6..9) it[i] = 0xFF.toByte() // logical screen width and height, little-endian
}

class AnimatedGifTest {
    @Test fun framesAdvanceByDuration() {
        // 50 ms, 200 ms, and 0 ms, which plays at the browsers' 100 ms
        val gif = assertNotNull(GifFrames.decode(gifOf(listOf(Color.RED, Color.GREEN, Color.BLUE), listOf(5, 20, 0))))
        assertEquals(listOf(50, 200, 100), gif.durations)
        assertEquals(8, gif.width); assertEquals(6, gif.height)

        val t = gif.timeline
        assertEquals(0, t.frameAt(0)); assertEquals(0, t.frameAt(49))
        assertEquals(1, t.frameAt(50)); assertEquals(1, t.frameAt(249))
        assertEquals(2, t.frameAt(250)); assertEquals(2, t.frameAt(349))
        assertEquals(0, t.frameAt(350)) // loops
        assertEquals(1, t.frameAt(350 + 50))
        assertEquals(50L, t.untilNext(0)); assertEquals(1L, t.untilNext(249)); assertEquals(30L, t.untilNext(320))

        // Each frame decodes to its own picture.
        fun centre(i: Int) = gif.frame(i).toPixelMap().let { it[4, 3] }
        assertEquals(androidx.compose.ui.graphics.Color.Red, centre(0))
        assertEquals(androidx.compose.ui.graphics.Color.Green, centre(1))
        assertEquals(androidx.compose.ui.graphics.Color.Blue, centre(2))
    }

    @Test fun aGifThatPlaysOnceStopsOnItsLastFrame() {
        val gif = assertNotNull(GifFrames.decode(gifOf(listOf(Color.RED, Color.GREEN), listOf(10, 10), loops = null)))
        assertEquals(1, gif.timeline.frameAt(10_000))
        assertNull(gif.timeline.untilNext(10_000)) // nothing more to show: the loop can stop
    }

    @Test fun aGifOverThePixelCapIsRefused() {
        assertNull(GifFrames.decode(hugeCanvasGif())) // 65535 × 65535: refused from the header, nothing allocated
        val small = gifOf(listOf(Color.RED, Color.GREEN), listOf(10, 10)) // 8 × 6
        assertNull(GifFrames.decode(small, maxPixels = 47))
        assertNotNull(GifFrames.decode(small, maxPixels = 48))
    }

    @Test fun framesBuildOnThePreviousOne() {
        // Frames decoded in order reuse the canvas; asked out of order they still come out right.
        val gif = assertNotNull(GifFrames.decode(gifOf(listOf(Color.RED, Color.GREEN, Color.BLUE), listOf(5, 5, 5))))
        fun centre(i: Int) = gif.frame(i).toPixelMap().let { it[4, 3] }
        assertEquals(androidx.compose.ui.graphics.Color.Blue, centre(2))
        assertEquals(androidx.compose.ui.graphics.Color.Red, centre(0))
        assertEquals(androidx.compose.ui.graphics.Color.Green, centre(1))
        assertEquals(androidx.compose.ui.graphics.Color.Blue, centre(2))
    }

    @Test fun notAGifIsNull() {
        assertNull(GifFrames.decode(byteArrayOf(1, 2, 3)))
    }
}
