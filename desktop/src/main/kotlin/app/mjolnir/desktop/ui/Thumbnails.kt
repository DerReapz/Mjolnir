package app.mjolnir.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** In-memory LRU of decoded thumbnails; a failed decode is remembered as `null` so it is not retried on every scroll. */
internal class ThumbnailCache(private val capacity: Int = 64) {
    private val map = object : LinkedHashMap<String, ImageBitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > capacity
    }

    fun has(key: String): Boolean = synchronized(map) { map.containsKey(key) }
    fun keys(): Set<String> = synchronized(map) { map.keys.toSet() }
    fun get(key: String): ImageBitmap? = synchronized(map) { map[key] }
    fun put(key: String, value: ImageBitmap?) { synchronized(map) { map[key] = value } }
}

private val cache = ThumbnailCache(64)

/** The image at [path] scaled to fit [maxPx], or null (not an image, unreadable). Reads the disk: call off the UI thread. */
internal fun loadThumbnail(path: String, maxPx: Int): ImageBitmap? {
    val key = "$path@$maxPx"
    if (cache.has(key)) return cache.get(key)
    val bitmap = try { decode(File(path), maxPx) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    cache.put(key, bitmap)
    return bitmap
}

/** Whether [loadThumbnail] holds a picture of [path] at any size (tests). */
internal fun thumbnailCached(path: String): Boolean = cache.keys().any { it.startsWith("$path@") }

/**
 * Like [loadThumbnail], but not kept in the thumbnail cache: for one big picture shown once (the chat image viewer),
 * which would otherwise hold tens of megabytes per entry. Null when it isn't an image ImageIO reads, or too big to decode.
 */
internal fun decodeUncached(file: File, maxPx: Int): ImageBitmap? =
    try { decode(file, maxPx) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

/** The ImageIO subsampling step for a [w]×[h] picture shown within [maxPx]: decoded at maxPx to 2 × maxPx on its long side. */
internal fun subsampleStep(w: Int, h: Int, maxPx: Int): Int = maxOf(1, maxOf(w, h) / maxPx)

private fun decode(file: File, maxPx: Int): ImageBitmap? = ImageIO.createImageInputStream(file)?.use { input ->
    val readers = ImageIO.getImageReaders(input)
    if (!readers.hasNext()) return@use null
    val reader = readers.next()
    try {
        reader.input = input
        val w = reader.getWidth(0); val h = reader.getHeight(0)
        val param: ImageReadParam = reader.defaultReadParam
        val step = subsampleStep(w, h, maxPx) // decode-time subsampling: a 48 MP photo never decodes at full size
        param.setSourceSubsampling(step, step, 0, 0)
        val src = reader.read(0, param)
        val scale = maxPx.toDouble() / maxOf(src.width, src.height)
        if (scale >= 1.0) return@use src.toComposeImageBitmap()
        val out = BufferedImage((src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, out.width, out.height, null)
        g.dispose()
        out.toComposeImageBitmap()
    } finally {
        reader.dispose()
    }
}

/** Decodes [path] off the UI thread; null until ready and when the file is no image. */
@Composable
internal fun rememberThumbnail(path: String, maxPx: Int): ImageBitmap? {
    var bitmap by remember(path, maxPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, maxPx) { bitmap = withContext(Dispatchers.IO) { loadThumbnail(path, maxPx) } }
    return bitmap
}
