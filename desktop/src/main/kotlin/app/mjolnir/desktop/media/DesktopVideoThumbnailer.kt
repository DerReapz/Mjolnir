package app.mjolnir.desktop.media

import app.mjolnir.client.ImageIoThumbnailer
import app.mjolnir.client.Thumb
import app.mjolnir.client.Thumbnailer
import app.mjolnir.client.videoRotation
import app.mjolnir.protocol.AttachmentKind
import java.awt.image.BufferedImage
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
 * upright size and a frame at 1 s (or halfway through a shorter one), turned by its rotation flag ([videoRotation]). The
 * JavaFX work runs on its own thread while the calling (IO) thread waits at most [TIMEOUT_MS] in all, JavaFX's start
 * included; on a timeout or any failure the video gets no thumbnail and is sent as a plain video tile (ruling CV-P2).
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
            val turn = videoRotation(file) // phones record portrait as landscape frames plus this flag
            JavaFx.runIfStarted {
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
                                val frame = turned(SwingFXUtils.fromFXImage(view.snapshot(null, null), null), turn)
                                val sideways = turn == 90 || turn == 270
                                val w = if (sideways) media.height else media.width
                                val h = if (sideways) media.width else media.height
                                if (w > 0 && h > 0) result.set(Thumb(ImageIoThumbnailer.jpegThumbnail(frame), w, h, length?.toLong()))
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
            JavaFx.runIfStarted { player?.dispose() } // the player of a capture that timed out too
        }
        return result.get()
    }

    /** [img] turned [degrees] clockwise (0, 90, 180 or 270). */
    internal fun turned(img: BufferedImage, degrees: Int): BufferedImage {
        if (degrees == 0) return img
        val sideways = degrees == 90 || degrees == 270
        val out = BufferedImage(if (sideways) img.height else img.width, if (sideways) img.width else img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.translate(out.width / 2.0, out.height / 2.0)
            g.rotate(Math.toRadians(degrees.toDouble()))
            g.drawImage(img, -img.width / 2, -img.height / 2, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
