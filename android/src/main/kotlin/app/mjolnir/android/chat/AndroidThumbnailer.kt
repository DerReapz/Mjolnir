package app.mjolnir.android.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import androidx.core.graphics.createBitmap
import app.mjolnir.client.Thumb
import app.mjolnir.client.Thumbnailer
import app.mjolnir.protocol.AttachmentKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Stills are decoded only from images of at most this many pixels (their real size, read from the header). */
const val STILL_MAX_PIXELS = 256_000_000L
/** A GIF animates only if its canvas (real size, from the header) is at most this many pixels; else it keeps its still. */
const val GIF_MAX_PIXELS = 4096L * 4096L

/** [w]×[h] scaled down (never up) so that its longest side is at most [maxSide]; at least 1×1. */
fun fitWithin(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
    val scale = minOf(1.0, maxSide.toDouble() / max(w, h))
    return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
}

/**
 * The picture in [file] (a GIF's first frame) decoded straight to at most [maxSide] px on its long side: the target
 * comes from the file's own header, never from what a sender claimed. Null when it can't be read, is over [maxPixels],
 * or doesn't fit in memory. [software] gives a bitmap that can be drawn on (for the thumbnail JPEG).
 */
fun decodeBitmap(
    file: File, maxSide: Int, maxPixels: Long = STILL_MAX_PIXELS, software: Boolean = false, onSize: (Int, Int) -> Unit = { _, _ -> },
): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val w = info.size.width; val h = info.size.height
        // GIFs don't downsample while decoding (a full-canvas buffer, natively): whatever the sender called it, cap them harder.
        val cap = if (info.mimeType.equals("image/gif", ignoreCase = true)) minOf(maxPixels, GIF_MAX_PIXELS) else maxPixels
        if (w <= 0 || h <= 0 || w.toLong() * h > cap) throw IOException("refused ${w}x$h")
        onSize(w, h)
        val (tw, th) = fitWithin(w, h, maxSide)
        decoder.setTargetSize(tw, th)
        if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/**
 * The GIF in [file] as an animation scaled to at most [maxSide] px, or null: over [maxBytes], a canvas over
 * [GIF_MAX_PIXELS] (from its header), a single frame, unreadable, or out of memory. The caller then keeps the still.
 */
fun decodeAnimated(file: File, maxSide: Int, maxBytes: Long): AnimatedImageDrawable? = try {
    if (file.length() > maxBytes) null
    else ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val w = info.size.width; val h = info.size.height
        if (w <= 0 || h <= 0 || w.toLong() * h > GIF_MAX_PIXELS) throw IOException("refused ${w}x$h")
        val (tw, th) = fitWithin(w, h, maxSide)
        decoder.setTargetSize(tw, th)
    } as? AnimatedImageDrawable
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/** What the viewer shows of a file: its animation, a full still, or neither (it keeps the thumbnail it has). */
data class ViewerPicture<A : Any, S : Any>(val animated: A? = null, val still: S? = null)

/**
 * Decides how the viewer decodes a file of [kind] and [bytes]. A GIF animates if [animate] can; when it can't (over the
 * byte cap or the canvas cap) it is never decoded whole: a still only up to [gifMaxBytes] and [GIF_MAX_PIXELS], else the
 * thumbnail stays. Other pictures get [still] up to [STILL_MAX_PIXELS]. [still] takes the pixel cap.
 */
fun <A : Any, S : Any> viewerPicture(
    kind: AttachmentKind, bytes: Long, gifMaxBytes: Long, animate: () -> A?, still: (maxPixels: Long) -> S?,
): ViewerPicture<A, S> {
    if (kind != AttachmentKind.GIF) return ViewerPicture(still = still(STILL_MAX_PIXELS))
    animate()?.let { return ViewerPicture(animated = it) }
    if (bytes > gifMaxBytes) return ViewerPicture()
    return ViewerPicture(still = still(GIF_MAX_PIXELS))
}

/**
 * The phone's [Thumbnailer]: photos (JPEG, PNG, WebP, HEIC, BMP) and the first frame of GIFs through `ImageDecoder`,
 * decoded straight to at most [MAX_SIDE] px (a huge image is never decoded at full size) and saved as a JPEG of quality
 * 80 on white. Null for videos (until the video plan), other kinds, and anything it can't read.
 */
object AndroidThumbnailer : Thumbnailer {
    const val MAX_SIDE = 320
    private const val QUALITY = 80

    override fun thumbnail(file: File, kind: AttachmentKind): Thumb? {
        if (kind != AttachmentKind.IMAGE && kind != AttachmentKind.GIF) return null
        var width = 0; var height = 0
        val decoded = decodeBitmap(file, MAX_SIDE, software = true) { w, h -> width = w; height = h } ?: return null
        return try {
            val out = createBitmap(decoded.width, decoded.height) // JPEG has no alpha: white behind
            try {
                Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
                val bytes = ByteArrayOutputStream()
                if (!out.compress(Bitmap.CompressFormat.JPEG, QUALITY, bytes)) return null
                Thumb(bytes.toByteArray(), width, height, null)
            } finally {
                out.recycle()
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            decoded.recycle()
        }
    }
}
