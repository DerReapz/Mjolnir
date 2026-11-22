package app.mjolnir.desktop.media

import app.mjolnir.client.videoRotation
import app.mjolnir.desktop.markOfTheWeb
import app.mjolnir.desktop.ui.VideoEngine
import app.mjolnir.desktop.ui.playerFileName
import app.mjolnir.protocol.Attachment
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color
import javafx.util.Duration
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The JavaFX runtime, started once and only when a video is first played or thumbnailed, never at app start. It keeps
 * running when its last window closes (the app has none of its own).
 */
internal object JavaFx {
    /** How long a start may take before JavaFX counts as unavailable here. */
    private const val START_TIMEOUT_MS = 10_000L
    private var startup: CompletableFuture<Unit>? = null

    /**
     * Starts JavaFX unless it runs, waiting at most [timeoutMs]. Throws when it can't start here: no display (where it
     * would hang), missing natives, or a start that doesn't finish in time (it is then not tried again). It blocks, so
     * call it off the UI thread; the UI thread only uses [runIfStarted].
     */
    fun start(timeoutMs: Long = START_TIMEOUT_MS) {
        val future = synchronized(this) {
            startup ?: CompletableFuture<Unit>().also { f ->
                startup = f
                if (!displayAvailable()) f.completeExceptionally(UnsupportedOperationException("no display for JavaFX"))
                else Thread({
                    try {
                        try {
                            Platform.startup {}
                        } catch (_: IllegalStateException) {
                            // already started (a JFXPanel got there first)
                        }
                        Platform.setImplicitExit(false)
                        f.complete(Unit)
                    } catch (e: Throwable) {
                        f.completeExceptionally(e)
                    }
                }, "javafx-startup").apply { isDaemon = true }.start()
            }
        }
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.completeExceptionally(e) // a hung start counts as failed from now on
            throw UnsupportedOperationException("JavaFX did not start", e)
        } catch (e: ExecutionException) {
            throw UnsupportedOperationException("JavaFX can't start here", e.cause)
        }
    }

    /** False on a headless JVM, or on Linux without X or Wayland, where JavaFX's start never returns. */
    private fun displayAvailable(): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        val linux = System.getProperty("os.name").lowercase().contains("linux")
        return !linux || System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null
    }

    /** True once JavaFX has started (and not failed to). */
    fun started(): Boolean = synchronized(this) { startup }?.let { it.isDone && !it.isCompletedExceptionally } == true

    /**
     * Runs [block] on the JavaFX thread once JavaFX has started: at once if it has, after the start if one is under way,
     * never if it failed or was never started. Never blocks and never throws, so the UI thread may call it.
     */
    fun runIfStarted(block: () -> Unit) {
        val future = synchronized(this) { startup } ?: return
        future.thenRun {
            runCatching { if (Platform.isFxApplicationThread()) block() else Platform.runLater(block) }
        }
    }

    /**
     * Opens the cached [file] of [att] in the system player, unless its name is not a video's ([playerFileName]). The
     * cache names files by id, so a copy with the attachment's name (and so its extension) is made in the temp folder
     * first, once.
     */
    fun openInPlayer(att: Attachment, file: File) {
        Thread({
            try {
                val copy = copyForPlayer(att, file, File(System.getProperty("java.io.tmpdir"), "mjolnir-videos"), ::markOfTheWeb) ?: return@Thread
                Desktop.getDesktop().open(copy)
            } catch (_: Exception) {
                // nothing to open it with: the bubble keeps offering Download
            }
        }, "open-video").apply { isDaemon = true }.start()
    }

    /**
     * The copy of [file] under [att]'s name in [root] that the system player opens, tagged by [mark] (the Mark of the
     * Web); null, with nothing written, when the name is not a video's.
     */
    internal fun copyForPlayer(att: Attachment, file: File, root: File, mark: (File) -> Unit): File? {
        val name = playerFileName(att.name) ?: return null
        val dir = File(root, att.fileId).apply { mkdirs() }
        val copy = File(dir, name)
        if (copy.length() != file.length()) Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
        mark(copy)
        copy.deleteOnExit(); dir.deleteOnExit()
        return copy
    }
}

/** A [VideoEngine] on a JavaFX [MediaPlayer]; its picture is a [MediaView] shown through [panel]. */
class JavaFxEngine : VideoEngine {
    private val _position = MutableStateFlow(0L)
    private val _duration = MutableStateFlow<Long?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _playing = MutableStateFlow(false)
    override val positionMs: StateFlow<Long> = _position.asStateFlow()
    override val durationMs: StateFlow<Long?> = _duration.asStateFlow()
    override val error: StateFlow<String?> = _error.asStateFlow()
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** Touched on the JavaFX thread only. */
    private var player: MediaPlayer? = null
    private var view: MediaView? = null
    @Volatile private var released = false
    /** Degrees clockwise the picture is turned to show upright (a phone's portrait flag). */
    @Volatile private var rotation = 0

    /** Opens [file]. Blocks while JavaFX starts (up to 10 s), so call it off the UI thread; throws when JavaFX can't. */
    override fun load(file: File) {
        JavaFx.start()
        rotation = videoRotation(file)
        JavaFx.runIfStarted {
            if (released) return@runIfStarted
            try {
                val media = Media(file.toURI().toString())
                media.setOnError { fail(media.error) }
                val p = MediaPlayer(media)
                p.setOnError { fail(p.error) }
                p.setOnReady { _duration.value = media.duration.toMillis().takeIf { it.isFinite() && it > 0 }?.toLong() }
                p.currentTimeProperty().addListener { _, _, t -> _position.value = t.toMillis().toLong() }
                p.setOnPlaying { _playing.value = true }
                p.setOnPaused { _playing.value = false }
                p.setOnStopped { _playing.value = false }
                p.setOnEndOfMedia { // back to the start, paused, ready to play again
                    p.pause(); p.seek(Duration.ZERO); _playing.value = false
                }
                player = p
                view?.mediaPlayer = p
            } catch (e: Exception) { // MediaException: unsupported format or codec
                fail(e)
            }
        }
    }

    private fun fail(e: Throwable?) {
        _playing.value = false
        _error.value = e?.message ?: "can't play"
    }

    // These run on the UI thread: they hand the work to JavaFX and never block or throw.
    override fun play() {
        _playing.value = true
        JavaFx.runIfStarted { player?.play() }
    }

    override fun pause() {
        _playing.value = false
        JavaFx.runIfStarted { player?.pause() }
    }

    override fun seek(ms: Long) {
        _position.value = ms
        JavaFx.runIfStarted { player?.seek(Duration.millis(ms.toDouble())) }
    }

    override fun release() {
        released = true
        _playing.value = false
        JavaFx.runIfStarted {
            view?.mediaPlayer = null
            player?.dispose()
            player = null
        }
    }

    /**
     * A Swing panel showing the video, scaled to fit with its aspect kept, on black, turned upright. Call on the AWT
     * thread, after [load] succeeded (JavaFX is then running; otherwise the panel stays black).
     */
    internal fun panel(): JComponent {
        if (!JavaFx.started()) return JPanel().apply { background = java.awt.Color.BLACK }
        val panel = JFXPanel()
        val turn = rotation
        JavaFx.runIfStarted {
            view?.mediaPlayer = null // a panel shown before (the bubble was covered, then shown again)
            val v = MediaView(player).apply { isPreserveRatio = true; isSmooth = true; rotate = turn.toDouble() }
            view = v
            val scene = Scene(StackPane(v), Color.BLACK)
            val sideways = turn == 90 || turn == 270 // the fit box is the view's own, before turning
            v.fitWidthProperty().bind(if (sideways) scene.heightProperty() else scene.widthProperty())
            v.fitHeightProperty().bind(if (sideways) scene.widthProperty() else scene.heightProperty())
            panel.scene = scene
        }
        return panel
    }
}
