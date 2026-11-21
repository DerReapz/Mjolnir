package app.mjolnir.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import io.nayuki.qrcodegen.QrCode
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import androidx.compose.ui.graphics.asComposeImageBitmap

/** The QR code's light modules and quiet zone (the mockup's `#F5F2EC`). */
internal val QR_LIGHT = Color(0xFFF5F2EC)
private val QR_DARK = Color(0xFF141414)

/**
 * [text] as a QR code (error correction M): one square of [scale] px per module, dark on [QR_LIGHT], with a quiet
 * zone of [border] modules on every side.
 */
internal fun qrImage(text: String, scale: Int = 8, border: Int = 2): ImageBitmap {
    val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
    val side = (qr.size + 2 * border) * scale
    val pixels = IntArray(side * side)
    for (y in 0 until side) for (x in 0 until side) {
        val dark = qr.getModule(x / scale - border, y / scale - border) // out of range reads as light
        pixels[y * side + x] = (if (dark) QR_DARK else QR_LIGHT).toArgb()
    }
    val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(side, side, ColorAlphaType.PREMUL)) }
    val bytes = java.nio.ByteBuffer.allocate(pixels.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(pixels) }
    bitmap.installPixels(bytes.array())
    return bitmap.asComposeImageBitmap()
}
