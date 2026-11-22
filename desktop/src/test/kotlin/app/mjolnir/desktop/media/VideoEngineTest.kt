package app.mjolnir.desktop.media

import app.mjolnir.client.Messages
import app.mjolnir.desktop.ui.FakeEngine
import app.mjolnir.desktop.ui.NowPlaying
import app.mjolnir.desktop.ui.videoSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
