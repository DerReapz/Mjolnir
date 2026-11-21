package app.mjolnir.desktop

import androidx.compose.ui.graphics.toArgb
import app.mjolnir.desktop.ui.QR_LIGHT
import app.mjolnir.desktop.ui.qrImage
import app.mjolnir.desktop.ui.qrImageFitting
import app.mjolnir.desktop.ui.qrScale
import io.nayuki.qrcodegen.QrCode
import kotlin.test.Test
import kotlin.test.assertEquals

class QrCodeTest {
    private val link = "https://relay.example.com/g/K7Q-29P_aB3dEfGhIjKlMnOpQrStUvWxYz012345678"

    @Test fun encodesLink() {
        val expected = QrCode.encodeText(link, QrCode.Ecc.MEDIUM)
        val scale = 6; val border = 4
        val image = qrImage(link, scale, border)
        assertEquals((expected.size + 2 * border) * scale, image.width)
        val px = IntArray(image.width * image.height).also { image.readPixels(it) }
        fun at(x: Int, y: Int) = px[(y + border) * scale * image.width + (x + border) * scale + scale / 2 + (scale / 2) * image.width]
        for (y in 0 until expected.size) for (x in 0 until expected.size) {
            val dark = at(x, y) != QR_LIGHT.toArgb()
            assertEquals(expected.getModule(x, y), dark, "module $x,$y")
        }
        assertEquals(QR_LIGHT.toArgb(), px[0]) // the quiet zone is light
    }

    @Test fun defaultQuietZoneIsFourModules() {
        val expected = QrCode.encodeText(link, QrCode.Ecc.MEDIUM)
        assertEquals((expected.size + 2 * 4) * 8, qrImage(link).width)
    }

    @Test fun fittingImageUsesAWholeScaleWithinTheTarget() {
        assertEquals(4, qrScale(41, 168))
        assertEquals(1, qrScale(41, 10)) // never below one pixel per module
        val expected = QrCode.encodeText(link, QrCode.Ecc.MEDIUM)
        val modules = expected.size + 8
        val image = qrImageFitting(link, 168)
        assertEquals(0, image.width % modules)
        assertEquals(true, image.width <= 168)
        assertEquals(image.width, image.height)
    }
}
