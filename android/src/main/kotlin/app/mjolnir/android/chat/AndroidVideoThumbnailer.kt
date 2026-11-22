package app.mjolnir.android.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import app.mjolnir.client.Thumb
import app.mjolnir.client.Thumbnailer
import app.mjolnir.protocol.AttachmentKind
import java.io.ByteArrayOutputStream
import java.io.File

/** A video's stored [w]×[h] as it shows: a phone's portrait clip is stored landscape with a rotation of 90 or 270. */
fun orientedSize(w: Int, h: Int, rotation: Int): Pair<Int, Int> = if (Math.floorMod(rotation, 180) == 90) h to w else w to h

/**
 * The phone's video [Thumbnailer], through `MediaMetadataRetriever`: the length, the upright size (its rotation applied),
 * and the frame at 1 s (or the middle of a shorter clip) scaled to at most 320 px as a JPEG of quality 80. Null when the
 * video can't be read; the send then goes without a thumbnail and the bubble shows a plain tile. Blocking.
 */
object AndroidVideoThumbnailer : Thumbnailer {
    private const val FRAME_AT_US = 1_000_000L
    private const val QUALITY = 80

    override fun thumbnail(file: File, kind: AttachmentKind): Thumb? {
        if (kind != AttachmentKind.VIDEO) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            fun key(k: Int) = retriever.extractMetadata(k)?.toLongOrNull()
            val durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: return null
            val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: return null
            if (w <= 0 || h <= 0) return null
            val (width, height) = orientedSize(w, h, key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0)
            val atUs = if (durationMs != null && durationMs in 1 until 2_000) durationMs * 500 else FRAME_AT_US
            // The frame comes back upright; asked for at most 320 × 320 it keeps its aspect.
            val frame = retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, AndroidThumbnailer.MAX_SIDE, AndroidThumbnailer.MAX_SIDE)
                ?: return null
            val jpeg = try { jpeg(frame) } finally { frame.recycle() } ?: return null
            Thumb(jpeg, width, height, durationMs)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** [frame] as a JPEG, scaled down first if the retriever gave more than 320 px a side. */
    private fun jpeg(frame: Bitmap): ByteArray? {
        val (tw, th) = fitWithin(frame.width, frame.height, AndroidThumbnailer.MAX_SIDE)
        val scaled = if (tw == frame.width && th == frame.height) frame else frame.scale(tw, th)
        return try {
            val bytes = ByteArrayOutputStream()
            if (scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, bytes)) bytes.toByteArray() else null
        } finally {
            if (scaled !== frame) scaled.recycle()
        }
    }
}
