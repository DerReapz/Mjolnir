package app.mjolnir.desktop.media

import app.mjolnir.client.ImageIoThumbnailer
import app.mjolnir.client.Thumb
import app.mjolnir.client.Thumbnailer
import app.mjolnir.protocol.AttachmentKind
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView

/**
 * The desktop's [Thumbnailer]: pictures through [ImageIoThumbnailer]; a video through JavaFX, capturing its length, its
 * size and a frame at 1 s (or halfway through a shorter one). The JavaFX work runs on its own thread while the calling
 * (IO) thread waits at most [TIMEOUT_MS] in all, JavaFX's start included; on a timeout or any failure the video gets no
 * thumbnail and is sent as a plain video tile (ruling CV-P2).
 */
object DesktopVideoThumbnailer : Thumbnailer {
    private const val TIMEOUT_MS = 10_000L
    private const val FRAME_AT_MS = 1_000.0

    override fun thumbnail(file: File, kind: AttachmentKind): Thumb? =
        if (kind == AttachmentKind.VIDEO) video(file) else ImageIoThumbnailer.thumbnail(file, kind)

    private fun video(file: File): Thumb? {
        val done = CountDownLatch(1)
        val result = AtomicReference<Thumb?>(null)
        var player: MediaPlayer? = null
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        try {
            JavaFx.start(TIMEOUT_MS)
            JavaFx.run {
                try {
                    val media = Media(file.toURI().toString())
                    val p = MediaPlayer(media).also { player = it }
                    val view = MediaView(p)
                    Scene(Group(view)) // a frame is drawn only for a view in a scene
                    p.isMute = true
                    p.setOnError { done.countDown() }
                    p.setOnReady {
                        val length = media.duration.toMillis().takeIf { it.isFinite() && it > 0 }
                        val at = if (length != null) minOf(FRAME_AT_MS, length / 2) else 0.0
                        var captured = false
                        fun capture() {
                            if (captured) return
                            captured = true
                            p.pause()
                            try {
                                val frame = SwingFXUtils.fromFXImage(view.snapshot(null, null), null)
                                if (media.width > 0 && media.height > 0) {
                                    result.set(Thumb(ImageIoThumbnailer.jpegThumbnail(frame), media.width, media.height, length?.toLong()))
                                }
                            } finally {
                                done.countDown()
                            }
                        }
                        // Played muted up to the frame: a paused, seeked player may not have decoded one yet.
                        p.currentTimeProperty().addListener { _, _, t -> if (t.toMillis() >= at) capture() }
                        p.setOnEndOfMedia { capture() }
                        p.play()
                    }
                } catch (e: Exception) {
                    done.countDown()
                }
            }
            done.await((deadline - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            return null // JavaFX can't start here
        } catch (e: LinkageError) {
            return null // its natives are missing
        } finally {
            runCatching { JavaFx.run { player?.dispose() } } // the player of a capture that timed out too
        }
        return result.get()
    }
}
