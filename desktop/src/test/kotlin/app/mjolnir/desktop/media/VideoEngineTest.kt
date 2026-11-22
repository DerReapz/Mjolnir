package app.mjolnir.desktop.media

import app.mjolnir.client.Messages
import app.mjolnir.desktop.ui.FakeEngine
import app.mjolnir.desktop.ui.NowPlaying
import app.mjolnir.desktop.ui.playerFileName
import app.mjolnir.desktop.ui.videoSize
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoEngineTest {
    @Test fun timeTextFormats() {
        assertEquals("0:16 / 0:42", Messages.videoTime(16_000, 42_000))
        assertEquals("1:02:03 / 1:10:00", Messages.videoTime(3_723_000, 4_200_000))
    }

    @Test fun nowPlayingPausesThePrevious() {
        val a = FakeEngine(); val b = FakeEngine()
        NowPlaying.start(a); a.play()
        NowPlaying.start(b); b.play()
        assertFalse(a.playing.value); assertTrue(b.playing.value)
        NowPlaying.stopped(b)
        NowPlaying.start(a) // nothing current: nothing else paused
        assertTrue(b.playing.value)
        NowPlaying.stopped(a)
    }

    @Test fun bubbleSizes() {
        assertEquals(320.dp to 180.dp, videoSize(1920 to 1080))
        assertEquals(320.dp to 180.dp, videoSize(160 to 90)) // small videos fill the 320 px bubble
        assertEquals(225.dp to 400.dp, videoSize(1080 to 1920))
        assertEquals(320.dp to 180.dp, videoSize(null))
    }

    // Ruling CV-R4: only a name with a video extension is ever handed to the system (which runs whatever it is).
    @Test fun onlyVideoNamesOpenInPlayer() {
        assertEquals("clip.mp4", playerFileName("clip.mp4"))
        assertEquals("Holiday.MOV", playerFileName("Holiday.MOV"))
        assertEquals("a.webm", playerFileName("a.webm"))
        for (name in listOf("evil.exe", "clip.mp4.exe", "x.lnk", "a.hta", "run.js", "go.bat", "s.scr", "setup.msi", "clip", "mp4")) {
            assertNull(playerFileName(name), name)
        }
    }

    @Test fun hostileVideoIsNeverCopiedForThePlayer() {
        val tmp = Files.createTempDirectory("player-copies").toFile()
        val cached = File(tmp, "v1").apply { writeBytes(ByteArray(10) { it.toByte() }) }
        val root = File(tmp, "copies")
        val marked = mutableListOf<File>()
        fun att(name: String) = Attachment("v1", AttachmentKind.VIDEO, name, 10, "video/mp4")
        for (name in listOf("evil.exe", "clip.mp4.exe", "x.lnk")) assertNull(JavaFx.copyForPlayer(att(name), cached, root, marked::add), name)
        assertTrue(root.walk().none { it.isFile }, root.walk().toList().toString())
        assertTrue(marked.isEmpty())
        val copy = JavaFx.copyForPlayer(att("clip.mp4"), cached, root, marked::add)!!
        assertEquals("clip.mp4", copy.name)
        assertEquals(cached.readBytes().toList(), copy.readBytes().toList())
        assertEquals(listOf(copy), marked) // the Mark of the Web, as on a saved chat file
        tmp.deleteRecursively()
    }

    @Test fun playerCopiesAreDeleted() {
        val root = Files.createTempDirectory("player-copies").toFile()
        File(root, "v1").mkdirs(); File(root, "v1/clip.mp4").writeText("x")
        deletePlayerCopies(root)
        assertFalse(root.exists())
        deletePlayerCopies(root) // nothing there: fine
    }
}
