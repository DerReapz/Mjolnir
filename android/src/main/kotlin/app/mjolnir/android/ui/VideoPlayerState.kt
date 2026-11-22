package app.mjolnir.android.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mjolnir.client.Messages
import app.mjolnir.protocol.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which video: an attachment ([fileId]) of a message. */
data class VideoKey(val messageId: String, val fileId: String)

/** What the player reports: still fetching, ready (playing or paused), or at the end. */
enum class VideoPhase { BUFFERING, READY, ENDED }

/** What the bubble and the full screen show for the video in the player. */
enum class VideoStatus { LOADING, BUFFERING, PLAYING, PAUSED, ENDED, UNAVAILABLE, DELETED }

/** The video in the app's player: how it is doing, how far it is, and whether the full screen shows it. */
data class VideoUi(val key: VideoKey, val status: VideoStatus, val positionMs: Long, val durationMs: Long?, val fullScreen: Boolean = false)

interface VideoPlayerListener {
    /** The player's phase changed, or it started or stopped playing. */
    fun onPlayback(phase: VideoPhase, playing: Boolean)
    /** The stream failed for good: a 401 or 404, a codec it can't decode, or the network gone past its retries. */
    fun onError()
}

/** Streams one chat video at a time. The real one wraps ExoPlayer; tests use a fake. Driven from the main thread. */
interface VideoPlayer {
    /** Opens chat file [fileId] (replacing whatever was open) at [startMs]; it plays once ready if [play] was called. */
    fun load(fileId: String, startMs: Long)
    fun play()
    fun pause()
    fun seek(ms: Long)
    val positionMs: Long
    /** Known once the stream is open. */
    val durationMs: Long?
    /** Stops and frees the player; it is not used again. */
    fun release()
    fun setListener(listener: VideoPlayerListener?)
}

/** The line a video shows instead of its picture, or null when it plays (or can). */
fun videoNotice(status: VideoStatus): String? = when (status) {
    VideoStatus.UNAVAILABLE -> Messages.VIDEO_UNAVAILABLE
    VideoStatus.DELETED -> Messages.MESSAGE_DELETED
    else -> null
}

/** An inline video is at most 320 dp wide (and never wider than its column) and 400 dp tall. */
internal val VIDEO_MAX_WIDTH = 320.dp
internal val VIDEO_MAX_HEIGHT = 400.dp

/**
 * The inline size of a video of [size] px (the upright picture): as big as fits in [maxWidth] (capped at
 * [VIDEO_MAX_WIDTH]) by [VIDEO_MAX_HEIGHT] with its aspect kept; the mockup's 250 × 150 tile when its size is unknown.
 */
fun inlineVideoSize(size: Pair<Int, Int>?, maxWidth: Dp): Pair<Dp, Dp> {
    val (w, h) = size?.takeIf { it.first > 0 && it.second > 0 } ?: return 250.dp to 150.dp
    val boxW = minOf(maxWidth, VIDEO_MAX_WIDTH).value
    val maxH = VIDEO_MAX_HEIGHT.value
    // Width-limited or height-limited, whichever bites first (products before division keep round sizes exact).
    val (fw, fh) = if (boxW * h <= maxH * w) boxW to h * boxW / w else w * maxH / h to maxH
    return fw.coerceIn(minOf(48f, boxW), boxW).dp to fh.coerceIn(32f, maxH).dp
}

/**
 * The app's one video player, shared by every bubble and the full screen: playing a video replaces the one before it
 * (one at a time, and one ExoPlayer in memory), and the full screen takes over the same player, so it keeps its
 * position. [now] says which video it holds and how it is doing. Call from the main thread.
 */
class VideoPlayerState(private val newPlayer: () -> VideoPlayer) {
    private val _now = MutableStateFlow<VideoUi?>(null)
    val now: StateFlow<VideoUi?> = _now.asStateFlow()

    /** The player while a video is open; the screen shows its picture. */
    var player: VideoPlayer? = null
        private set

    private var loaded = false // the open stream has been ready once (else buffering counts as loading)

    private val listener = object : VideoPlayerListener {
        override fun onPlayback(phase: VideoPhase, playing: Boolean) {
            val ui = _now.value ?: return
            if (ui.status == VideoStatus.UNAVAILABLE || ui.status == VideoStatus.DELETED) return
            if (phase != VideoPhase.BUFFERING) loaded = true
            val status = when (phase) {
                VideoPhase.BUFFERING -> if (loaded) VideoStatus.BUFFERING else VideoStatus.LOADING
                VideoPhase.READY -> if (playing) VideoStatus.PLAYING else VideoStatus.PAUSED
                VideoPhase.ENDED -> VideoStatus.ENDED
            }
            _now.value = snapshot(ui).copy(status = status)
        }

        override fun onError() {
            val ui = _now.value ?: return
            if (ui.status == VideoStatus.DELETED) return
            player?.pause()
            _now.value = snapshot(ui).copy(status = VideoStatus.UNAVAILABLE)
        }
    }

    /** [ui] with the player's current position and length. */
    private fun snapshot(ui: VideoUi): VideoUi {
        val p = player ?: return ui
        return ui.copy(positionMs = p.positionMs, durationMs = p.durationMs ?: ui.durationMs)
    }

    /** Plays [key]: resumes it when it is the one open (from the start once it ended), else opens it in place of the last. */
    fun play(key: VideoKey) {
        val ui = _now.value
        val p = player
        if (ui != null && ui.key == key && p != null && ui.status != VideoStatus.UNAVAILABLE && ui.status != VideoStatus.DELETED) {
            if (ui.status == VideoStatus.ENDED) p.seek(0)
            p.play()
            return
        }
        open(key, 0, fullScreen = false)
    }

    private fun open(key: VideoKey, startMs: Long, fullScreen: Boolean) {
        val p = player ?: newPlayer().also { it.setListener(listener); player = it }
        loaded = false
        _now.value = VideoUi(key, VideoStatus.LOADING, startMs, null, fullScreen)
        p.load(key.fileId, startMs)
        p.play()
    }

    /** Plays or pauses the open video. */
    fun toggle() {
        val ui = _now.value ?: return
        if (ui.status == VideoStatus.PLAYING || ui.status == VideoStatus.BUFFERING) pause() else play(ui.key)
    }

    /** Pauses the open video (an overlay covers it, it left the screen, the app went to the background). */
    fun pause() {
        player?.pause()
    }

    fun seek(ms: Long) {
        val p = player ?: return
        val ui = _now.value ?: return
        val target = ms.coerceIn(0, p.durationMs ?: ui.durationMs ?: Long.MAX_VALUE)
        p.seek(target)
        _now.value = snapshot(ui)
    }

    /** −10 s / +10 s on the full screen. */
    fun skip(deltaMs: Long) {
        val p = player ?: return
        seek(p.positionMs + deltaMs)
    }

    /** After `Video unavailable`: opens the stream again where it stopped. */
    fun retry() {
        val ui = _now.value ?: return
        if (ui.status != VideoStatus.UNAVAILABLE) return
        open(ui.key, ui.positionMs, ui.fullScreen)
    }

    /** Called while a video shows: its position and length move on. */
    fun tick() {
        val ui = _now.value ?: return
        _now.value = snapshot(ui)
    }

    /** The full screen opens on [key], which must be the open video; the same player carries on there. */
    fun openFullScreen(key: VideoKey) {
        val ui = _now.value ?: return
        if (ui.key == key) _now.value = snapshot(ui).copy(fullScreen = true)
    }

    /** The full screen closed: the video goes back to its bubble (or is forgotten when its message was deleted). */
    fun closeFullScreen() {
        val ui = _now.value ?: return
        _now.value = if (ui.status == VideoStatus.DELETED) null else snapshot(ui).copy(fullScreen = false)
    }

    /** The chat's messages changed: if the open video's message was deleted, it stops and says so. */
    fun messagesChanged(messages: List<ChatMessage>) {
        val ui = _now.value ?: return
        if (messages.any { it.id == ui.key.messageId && it.deleted }) {
            val last = snapshot(ui)
            freePlayer()
            _now.value = last.copy(status = VideoStatus.DELETED)
        }
    }

    /**
     * [key]'s bubble left the composition: when it holds the player, the player is freed (a deleted video's notice stays).
     * While the full screen shows it, the bubble going (the list scrolled under the full screen) changes nothing: closing
     * the full screen hands it back, and the chat screen pauses it if its bubble isn't shown ([visibleMessages]).
     */
    fun release(key: VideoKey) {
        val ui = _now.value ?: return
        if (ui.key != key || ui.fullScreen) return
        freePlayer()
        if (ui.status != VideoStatus.DELETED) _now.value = null
    }

    /**
     * The messages whose rows the chat list shows now: a video playing inline whose bubble isn't among them (scrolled
     * away, or not composed again after a rotation or after the full screen closed) pauses, so nothing plays unseen.
     */
    fun visibleMessages(messageIds: Set<String>) {
        val ui = _now.value ?: return
        if (ui.fullScreen || ui.key.messageId in messageIds) return
        pause()
    }

    /** Frees the player whatever it holds (the chat closed). */
    fun releaseAll() {
        freePlayer()
        _now.value = null
    }

    private fun freePlayer() {
        val p = player ?: return
        player = null
        p.setListener(null)
        try {
            p.release()
        } catch (_: RuntimeException) {
            // A player that fails to release is dropped all the same.
        }
    }
}
