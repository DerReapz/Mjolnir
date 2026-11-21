package app.mjolnir.client

import app.mjolnir.protocol.AttachmentKind
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/bmp")

private val MIME_BY_EXTENSION = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
    "heic" to "image/heic", "bmp" to "image/bmp", "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime",
    "webm" to "video/webm", "mkv" to "video/x-matroska", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
)

/** The media type of a file called [name]: from its extension for the media kinds, else what the platform guesses. */
fun mimeOf(name: String): String =
    MIME_BY_EXTENSION[name.substringAfterLast('.', "").lowercase()]
        ?: runCatching { Files.probeContentType(File(name).toPath()) }.getOrNull()
        ?: "application/octet-stream"

/** The bubble a file gets: by [mime], or by [name]'s extension when the type is missing or generic. */
fun classify(mime: String?, name: String): AttachmentKind {
    val type = mime?.substringBefore(';')?.trim()?.lowercase()
        ?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
        ?: MIME_BY_EXTENSION[name.substringAfterLast('.', "").lowercase()]
        ?: return AttachmentKind.FILE
    return when {
        type == "image/gif" -> AttachmentKind.GIF
        type in IMAGE_MIMES -> AttachmentKind.IMAGE
        type.startsWith("video/") -> AttachmentKind.VIDEO
        else -> AttachmentKind.FILE
    }
}

/**
 * A preview of a photo, GIF or video: [jpeg] (longest side at most 320 px) is uploaded as the attachment's thumbnail.
 * [width] and [height] are the original's size in pixels (the bubble's shape); [durationMs] is a video's length.
 */
class Thumb(val jpeg: ByteArray, val width: Int, val height: Int, val durationMs: Long?)

/** Makes the thumbnail of [file], or null when it can't (a corrupt image, a kind it does not handle). */
fun interface Thumbnailer {
    fun thumbnail(file: File, kind: AttachmentKind): Thumb?
}

/** How a picked file is sent: its [kind] and, for photos and GIFs (later videos), its [thumb]. */
data class MediaInfo(val kind: AttachmentKind, val thumb: Thumb?)

/**
 * The [MediaInfo] of [file] with media type [mime]. A photo or GIF that [thumbnailer] can't read (null or an exception)
 * is sent as a FILE. Videos keep their kind without a thumbnail until the video plan lands.
 */
fun mediaInfo(file: File, mime: String?, thumbnailer: Thumbnailer): MediaInfo {
    val kind = classify(mime, file.name)
    if (kind == AttachmentKind.FILE) return MediaInfo(kind, null)
    val thumb = try {
        thumbnailer.thumbnail(file, kind)
    } catch (e: Exception) {
        null
    }
    return when {
        thumb != null -> MediaInfo(kind, thumb)
        kind == AttachmentKind.VIDEO -> MediaInfo(kind, null)
        else -> MediaInfo(AttachmentKind.FILE, null)
    }
}

/**
 * The desktop's [Thumbnailer]: photos and the first frame of GIFs through ImageIO, read subsampled so a huge image never
 * decodes at full size, scaled to at most [MAX_SIDE] px and saved as a JPEG of quality 0.8. Null for videos (until the
 * video plan), other kinds, and anything ImageIO can't read (including WebP and HEIC, which it has no reader for).
 */
object ImageIoThumbnailer : Thumbnailer {
    const val MAX_SIDE = 320
    private const val QUALITY = 0.8f

    override fun thumbnail(file: File, kind: AttachmentKind): Thumb? {
        if (kind != AttachmentKind.IMAGE && kind != AttachmentKind.GIF) return null
        return try {
            ImageIO.createImageInputStream(file)?.use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
                try {
                    reader.setInput(input, true, true)
                    val width = reader.getWidth(0); val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0) return null
                    // Decode at most about twice the target size, then scale smoothly the rest of the way.
                    val step = max(1, max(width, height) / (MAX_SIDE * 2))
                    val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                    val decoded = reader.read(0, param) // index 0: a GIF's first frame
                    Thumb(jpeg(scaled(decoded)), width, height, null)
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun scaled(img: BufferedImage): BufferedImage {
        val scale = min(1.0, MAX_SIDE.toDouble() / max(img.width, img.height))
        val w = max(1, (img.width * scale).roundToInt()); val h = max(1, (img.height * scale).roundToInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB) // JPEG has no alpha: transparency becomes white
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(img, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun jpeg(img: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(bytes).use { out ->
                writer.output = out
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = QUALITY
                }
                writer.write(null, IIOImage(img, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }
}
