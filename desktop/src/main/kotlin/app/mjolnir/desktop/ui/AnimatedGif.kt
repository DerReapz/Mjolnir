package app.mjolnir.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameMillis
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

/** Browsers show a frame of 0 to 10 ms for 100 ms; so do we, or such GIFs would spin. */
private const val DEFAULT_FRAME_MS = 100
private const val MIN_FRAME_MS = 10
/** Decoded frames are kept (no decoding after the first loop) while all of them fit in this; bigger GIFs decode each frame as shown. */
private const val FRAME_CACHE_BYTES = 48L shl 20
/** A GIF whose canvas is bigger than this (4096 × 4096) is not animated: a sender could declare 65535 × 65535. */
internal const val GIF_MAX_PIXELS = 16_777_216L

/**
 * When each frame of an animation shows: frame i for [durations]`[i]` ms, looping forever, or [loops] more times after
 * the first play (0: plays once), then holding the last frame.
 */
internal class GifTimeline(val durations: List<Int>, private val loops: Int?) {
    private val total = durations.sumOf { it.toLong() }

    /** The frame showing [elapsedMs] after the start. */
    fun frameAt(elapsedMs: Long): Int {
        if (durations.size <= 1 || total <= 0) return 0
        if (finished(elapsedMs)) return durations.lastIndex
        var t = elapsedMs % total
        for ((i, d) in durations.withIndex()) {
            if (t < d) return i
            t -= d
        }
        return durations.lastIndex
    }

    /** Milliseconds from [elapsedMs] until the frame changes, or null once nothing changes any more. */
    fun untilNext(elapsedMs: Long): Long? {
        if (durations.size <= 1 || total <= 0 || finished(elapsedMs)) return null
        var t = elapsedMs % total
        for (d in durations) {
            if (t < d) return d - t
            t -= d
        }
        return null
    }

    private fun finished(elapsedMs: Long) = loops != null && elapsedMs >= total * (loops + 1L)
}

/** An animated GIF decoded with Skia's [Codec]: its size, each frame's own duration, and its frames on demand. */
internal class GifFrames private constructor(private val codec: Codec) {
    val width: Int = codec.width
    val height: Int = codec.height
    val durations: List<Int> = codec.framesInfo.map { if (it.duration <= MIN_FRAME_MS) DEFAULT_FRAME_MS else it.duration }
        .ifEmpty { listOf(DEFAULT_FRAME_MS) }
    val timeline = GifTimeline(durations, codec.repetitionCount.takeIf { it >= 0 })
    private val cacheFrames = width.toLong() * height * 4 * durations.size <= FRAME_CACHE_BYTES
    private val cached = arrayOfNulls<ImageBitmap>(durations.size)
    private var closed = false
    /** The canvas as of frame [canvasFrame]: the next frame is decoded on top of it, not again from its last independent frame. */
    private var canvas: Bitmap? = null
    private var canvasFrame = -1

    /** Frame [i] as a picture; decodes, so call it off the UI thread. Not after [close]. */
    fun frame(i: Int): ImageBitmap = synchronized(this) {
        cached[i] ?: run {
            check(!closed) { "closed" } // the native codec is gone
            val work = canvas ?: Bitmap().apply { allocPixels(codec.imageInfo) }.also { canvas = it }
            val onPrevious = i > 0 && canvasFrame == i - 1 &&
                runCatching { codec.readPixels(work, i, i - 1) }.isSuccess // Skia refuses when frame i can't build on i-1
            if (!onPrevious) codec.readPixels(work, i) // decodes what frame i needs from scratch
            canvasFrame = i
            // A copy of the pixels (the canvas is mutable, so Skia copies it): the canvas goes on to the next frame.
            org.jetbrains.skia.Image.makeFromBitmap(work).toComposeImageBitmap().also { if (cacheFrames) cached[i] = it }
        }
    }

    fun close() = synchronized(this) { if (!closed) { closed = true; codec.close(); canvas?.close(); canvas = null } }

    companion object {
        /**
         * [bytes] as an animation, or null when Skia can't read them or the canvas is over [maxPixels] (read from the
         * header, before anything is allocated): then the bubble keeps its still.
         */
        fun decode(bytes: ByteArray, maxPixels: Long = GIF_MAX_PIXELS): GifFrames? = try {
            val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
            if (codec.width <= 0 || codec.height <= 0 || codec.width.toLong() * codec.height > maxPixels) { codec.close(); null }
            else GifFrames(codec)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}

/**
 * Plays [gif], each frame for its own duration. It runs only while [playing] (the window is showing) and while some of
 * it is on screen; off screen it holds its frame and resumes there.
 */
@Composable
internal fun AnimatedGif(gif: GifFrames, contentDescription: String?, modifier: Modifier = Modifier, playing: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    var elapsed by remember(gif) { mutableLongStateOf(0L) }
    var shown by remember(gif) { mutableStateOf<ImageBitmap?>(null) }
    val index = gif.timeline.frameAt(elapsed)
    // The previous frame stays until this one is ready. Disposal cancels this before it closes the codec (frame() then refuses).
    LaunchedEffect(gif, index) { runCatching { withContext(Dispatchers.Default) { gif.frame(index) } }.getOrNull()?.let { shown = it } }
    LaunchedEffect(gif, playing && visible) {
        if (!(playing && visible)) return@LaunchedEffect
        while (true) {
            val wait = gif.timeline.untilNext(elapsed) ?: break
            val start = withFrameMillis { it }
            delay(wait)
            elapsed += (withFrameMillis { it } - start).coerceAtLeast(wait)
        }
    }
    DisposableEffect(gif) { onDispose { gif.close() } }
    val frameModifier = modifier.onGloballyPositioned { c -> visible = c.boundsInWindow().let { it.width > 0f && it.height > 0f } }
    shown?.let { Image(it, contentDescription, frameModifier, contentScale = ContentScale.Crop) }
        ?: androidx.compose.foundation.layout.Box(frameModifier)
}
