package app.mjolnir.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import app.mjolnir.client.Messages
import app.mjolnir.desktop.media.JavaFx
import app.mjolnir.desktop.media.JavaFxEngine
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.safeFileName
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** Plays one video file. The bubble drives it; [JavaFxEngine] is the real one, tests use a fake. */
interface VideoEngine {
    /** Opens [file]; an unplayable one fails here (any exception) or later through [error]. */
    fun load(file: File)
    fun play()
    fun pause()
    fun seek(ms: Long)
    val positionMs: StateFlow<Long>
    /** Known once the file is open. */
    val durationMs: StateFlow<Long?>
    /** Set when the video can't be played (an unsupported codec): the bubble then offers the system player. */
    val error: StateFlow<String?>
    /** True while playing; false when paused, ended, or paused by another video starting ([NowPlaying]). */
    val playing: StateFlow<Boolean>
    /** Stops playback and frees the player; the engine is not used again. */
    fun release()
}

/** The one video playing in the app: starting another pauses it. */
object NowPlaying {
    private var current: VideoEngine? = null

    /** [engine] is about to play: whatever played before is paused. */
    @Synchronized fun start(engine: VideoEngine) {
        val before = current
        current = engine
        if (before != null && before !== engine) before.pause()
    }

    /** [engine] was released: it no longer counts as playing. */
    @Synchronized fun stopped(engine: VideoEngine) {
        if (current === engine) current = null
    }
}

/** What the video bubbles need from the platform: engines, their picture, and the system player. */
interface VideoSupport {
    /** A new engine; throws when video can't play here at all (the bubble then shows its fallback). */
    fun engine(): VideoEngine
    /** [engine]'s picture, filling [modifier]'s bounds with the video's aspect kept. */
    @Composable fun Picture(engine: VideoEngine, modifier: Modifier)
    /** Opens the cached [file] of [att] in the system's video player; only for a [playerFileName]. */
    fun openInPlayer(att: Attachment, file: File)
}

/** No video playback: every video shows the fallback. */
object NoVideo : VideoSupport {
    override fun engine(): VideoEngine = throw UnsupportedOperationException("no video playback")
    @Composable override fun Picture(engine: VideoEngine, modifier: Modifier) = Box(modifier.background(Color.Black))
    override fun openInPlayer(att: Attachment, file: File) {}
}

/** Video through JavaFX: the [JavaFxEngine]'s `MediaView` in a `JFXPanel`, embedded with [SwingPanel]. */
object JavaFxVideo : VideoSupport {
    override fun engine(): VideoEngine = JavaFxEngine()

    @Composable override fun Picture(engine: VideoEngine, modifier: Modifier) {
        val fx = engine as JavaFxEngine
        SwingPanel(background = Color.Black, modifier = modifier, factory = { fx.panel() })
    }

    override fun openInPlayer(att: Attachment, file: File) = JavaFx.openInPlayer(att, file)
}

/**
 * The name [name]'s copy is handed to the system player under, or null when its extension is not a video's: the system
 * runs whatever the name says, so a "video" named `clip.exe` must never get there (ruling CV-R4).
 */
internal fun playerFileName(name: String): String? =
    safeFileName(name).takeIf { it.contains('.') && it.substringAfterLast('.').lowercase() in PLAYER_EXTENSIONS }

private val PLAYER_EXTENSIONS = setOf(
    "mp4", "m4v", "mov", "webm", "mkv", "avi", "wmv", "3gp", "3g2", "mpg", "mpeg", "ts", "m2ts", "mts", "ogv", "flv",
)

/** A video bubble is 320 px wide (portrait: 400 px tall at most), with the video's aspect kept; 320×180 when its size is unknown. */
internal fun videoSize(size: Pair<Int, Int>?): Pair<Dp, Dp> {
    val (w, h) = size ?: return VIDEO_WIDTH to 180.dp
    val scale = minOf(VIDEO_WIDTH.value / w, VIDEO_MAX_HEIGHT.value / h)
    return (w * scale).coerceAtLeast(48f).dp to (h * scale).coerceAtLeast(32f).dp
}

internal val VIDEO_WIDTH = 320.dp
internal val VIDEO_MAX_HEIGHT = 400.dp
internal val VIDEO_FRAME = Color(0xFF0E0E0E)
private val CONTROLS_FILL = Color(0xFF1A1A1A)

/**
 * Where the conversation's list is in the window, and whether an overlay (a dialog, the viewer, Info) covers it: a video
 * shows its player only wholly inside [viewport] and uncovered. No viewport known (a bubble outside a list): always shown.
 */
internal class VideoStage(val viewport: State<Rect?>, val covered: Boolean)

internal val LocalVideoStage = compositionLocalOf { VideoStage(mutableStateOf(null), covered = false) }

/** True when [inner] lies wholly inside this rectangle (to a pixel). */
internal fun Rect.encloses(inner: Rect): Boolean =
    inner.left >= left - 1 && inner.top >= top - 1 && inner.right <= right + 1 && inner.bottom <= bottom + 1

/** Reports this element's unclipped bounds in the window (part of it may be scrolled out of view). */
internal fun Modifier.trackBounds(onBounds: (Rect) -> Unit): Modifier = onGloballyPositioned {
    onBounds(Rect(it.positionInWindow(), it.size.toSize()))
}

/**
 * The inline player, per the mockup: the picture, then play/pause, the scrub bar and `0:16 / 0:42`. Without
 * [showPicture], [cover] takes the picture's place (the Swing panel would draw over overlays and the list's edges).
 */
@Composable
internal fun InlineVideoPlayer(
    engine: VideoEngine, video: VideoSupport, width: Dp, height: Dp, name: String, showPicture: Boolean = true,
    cover: @Composable () -> Unit = {},
) {
    val playing by engine.playing.collectAsState()
    val position by engine.positionMs.collectAsState()
    val duration by engine.durationMs.collectAsState()
    val toggle = {
        if (playing) engine.pause() else { NowPlaying.start(engine); engine.play() }
    }
    Column(Modifier.width(width).background(VIDEO_FRAME).semantics { contentDescription = "Video $name" }) {
        Box(Modifier.width(width).height(height).then(if (showPicture) Modifier.testTag("video-picture") else Modifier)) {
            if (showPicture) video.Picture(engine, Modifier.fillMaxSize()) else cover()
        }
        Row(
            Modifier.fillMaxWidth().background(CONTROLS_FILL).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(28.dp).background(Forge.accent, CircleShape)
                    .semantics { contentDescription = if (playing) "Pause" else "Play" }.clickable(role = Role.Button, onClick = toggle),
                contentAlignment = Alignment.Center,
            ) { if (playing) PauseGlyph(Forge.onAccent, 10.dp) else PlayGlyph(Forge.onAccent, 10.dp) }
            ScrubBar(position, duration, Modifier.weight(1f), onSeek = engine::seek)
            Text(
                Messages.videoTime(position, duration ?: 0), modifier = Modifier.testTag("video-time"),
                style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 11.sp, color = Forge.muted),
            )
        }
    }
}

/** A 4 px bar of how far the video is; clicking or dragging along it seeks. */
@Composable
private fun ScrubBar(positionMs: Long, durationMs: Long?, modifier: Modifier, onSeek: (Long) -> Unit) {
    val total = durationMs?.takeIf { it > 0 }
    val fraction = if (total != null) (positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    fun seekTo(x: Float, width: Int) { if (total != null && width > 0) onSeek(((x / width).coerceIn(0f, 1f) * total).toLong()) }
    Box(
        modifier.height(16.dp)
            .semantics { contentDescription = "Seek"; progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            .pointerInput(total) { detectTapGestures { seekTo(it.x, size.width) } }
            .pointerInput(total) { detectHorizontalDragGestures { change, _ -> seekTo(change.position.x, size.width) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Forge.border, RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Forge.accent, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
internal fun PlayGlyph(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val path = Path().apply {
        moveTo(this@Canvas.size.width * 0.15f, 0f)
        lineTo(this@Canvas.size.width, this@Canvas.size.height / 2)
        lineTo(this@Canvas.size.width * 0.15f, this@Canvas.size.height)
        close()
    }
    drawPath(path, color)
}

@Composable
private fun PauseGlyph(color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val bar = this.size.width * 0.32f
    drawRect(color, Offset(this.size.width * 0.08f, 0f), Size(bar, this.size.height))
    drawRect(color, Offset(this.size.width * 0.6f, 0f), Size(bar, this.size.height))
}
