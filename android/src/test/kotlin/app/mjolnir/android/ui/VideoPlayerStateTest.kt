package app.mjolnir.android.ui

import androidx.compose.ui.unit.dp
import app.mjolnir.android.chat.ChatFileAuth
import app.mjolnir.android.chat.chatFileUri
import app.mjolnir.android.chat.orientedSize
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.FileRequest
import app.mjolnir.client.Messages
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

class VideoPlayerStateTest {
    /** Records what the state asks of it; the test plays the player's side through [emit] and [failNow]. */
    private class FakePlayer : VideoPlayer {
        val loads = mutableListOf<Pair<String, Long>>()
        var playing = false
        var released = false
        var seeks = mutableListOf<Long>()
        override var positionMs = 0L
        override var durationMs: Long? = null
        private var listener: VideoPlayerListener? = null

        override fun load(fileId: String, startMs: Long) { check(!released); loads += fileId to startMs; positionMs = startMs }
        override fun play() { check(!released); playing = true }
        override fun pause() { check(!released); playing = false; emit(VideoPhase.READY) }
        override fun seek(ms: Long) { check(!released); seeks += ms; positionMs = ms }
        override fun release() { released = true; playing = false }
        override fun setListener(listener: VideoPlayerListener?) { this.listener = listener }

        fun emit(phase: VideoPhase) = listener?.onPlayback(phase, playing && phase == VideoPhase.READY)
        fun failNow() = listener?.onError()
    }

    private val players = mutableListOf<FakePlayer>()
    private val state = VideoPlayerState { FakePlayer().also { players += it } }
    private val a = VideoKey("m1", "f1")
    private val b = VideoKey("m2", "f2")

    private fun msg(id: String, deleted: Boolean = false) =
        ChatMessage(id, "c", Peer("p", "P", DeviceKind.PHONE), rev = 1, sentAtEpochMs = 0, deleted = deleted)

    @Test fun authHeaderOnDataSource() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String?, String?>>())
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) { // a tiny relay: records each request's path, Authorization and Range, answers 206
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                socket.use { s ->
                    val lines = s.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList() }
                    fun header(name: String) = lines.drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
                    seen += Triple(lines.first().split(' ')[1], header("Authorization"), header("Range"))
                    s.getOutputStream().write("HTTP/1.1 206 Partial Content\r\nContent-Length: 5\r\nConnection: close\r\n\r\nvideo".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }
        try {
            val fake = FakeRelayApi().apply { fileRequestBase = "http://127.0.0.1:${server.localPort}"; fileRequestToken = "tok-7" }
            val client = OkHttpClient.Builder().addInterceptor(ChatFileAuth(fake)).build()
            // What ExoPlayer's OkHttp data source asks for: the stand-in address of the file, from an offset on.
            val request = Request.Builder().url(chatFileUri("f1")).header("Range", "bytes=1000-").build()
            client.newCall(request).execute().use { assertEquals(206, it.code) }
            assertEquals(Triple("/v1/files/f1", "Bearer tok-7", "bytes=1000-"), seen.single())

            // The token is read for every request: after the phone registered again the next one carries the new one.
            fake.fileRequestToken = "tok-8"
            client.newCall(Request.Builder().url(chatFileUri("f1")).build()).execute().close()
            assertEquals("Bearer tok-8", seen.last().second)

            // A failure to build the request (no relay, config unreadable) is an IOException, never a crash on OkHttp's thread.
            fake.failAllWith = app.mjolnir.client.RelayException(app.mjolnir.client.RelayErrorKind.NETWORK, "down")
            assertFailsWith<IOException> { client.newCall(Request.Builder().url(chatFileUri("f1")).build()).execute() }
            assertEquals(2, seen.size)
        } finally {
            server.close()
        }
        assertEquals(FileRequest("http://relay.test/v1/files/f9", mapOf("Authorization" to "Bearer tok-1")), FakeRelayApi().chatFileRequest("f9"))
    }

    @Test fun portraitInlineKeepsAspect() {
        assertEquals(225.dp to 400.dp, inlineVideoSize(1080 to 1920, 320.dp)) // 400 tall at most, aspect kept
        assertEquals(320.dp to 180.dp, inlineVideoSize(1920 to 1080, 320.dp))
        assertEquals(160.dp to 90.dp, inlineVideoSize(1920 to 1080, 160.dp)) // a narrow column scales it down, still 16:9
        assertEquals(320.dp to 320.dp, inlineVideoSize(100 to 100, 320.dp)) // small videos grow to the bubble
        assertEquals(250.dp to 150.dp, inlineVideoSize(null, 320.dp)) // unknown size: the mockup's tile
        val (w, h) = inlineVideoSize(100 to 16000, 320.dp) // very tall: never thinner than a tap target
        assertEquals(400.dp, h); assertTrue(w >= 48.dp)
        // The sender's numbers are the upright picture: a phone's portrait clip (rotation 90) swaps its stored sides.
        assertEquals(1080 to 1920, orientedSize(1920, 1080, 90))
        assertEquals(1920 to 1080, orientedSize(1920, 1080, 180))
        assertEquals(1080 to 1920, orientedSize(1920, 1080, 270))
        assertEquals(1920 to 1080, orientedSize(1920, 1080, 0))
    }

    @Test fun playerReleasedOnDispose() {
        state.play(a)
        val p = players.single()
        assertEquals(listOf("f1" to 0L), p.loads); assertTrue(p.playing)
        state.release(b) // another bubble leaving changes nothing
        assertFalse(p.released)
        state.release(a)
        assertTrue(p.released); assertNull(state.now.value); assertNull(state.player)
        state.releaseAll() // nothing left: no second release
        assertEquals(1, players.size)
    }

    @Test fun onlyOneVideoPlays() {
        state.play(a)
        players.single().emit(VideoPhase.READY)
        assertEquals(VideoStatus.PLAYING, state.now.value?.status)
        state.play(b)
        // One player for the whole app: the second video replaces the first in it.
        val p = players.single()
        assertEquals(listOf("f1" to 0L, "f2" to 0L), p.loads)
        assertEquals(b, state.now.value?.key); assertEquals(VideoStatus.LOADING, state.now.value?.status)
        state.release(a) // the first bubble leaving the screen no longer owns the player
        assertFalse(p.released)
        state.pause()
        assertFalse(p.playing); assertEquals(VideoStatus.PAUSED, state.now.value?.status)
        state.toggle()
        assertTrue(p.playing)
    }

    @Test fun deletedDuringPlaybackStops() {
        state.play(a)
        players.single().emit(VideoPhase.READY)
        state.messagesChanged(listOf(msg("m1"), msg("m2")))
        assertEquals(VideoStatus.PLAYING, state.now.value?.status)
        state.messagesChanged(listOf(msg("m1", deleted = true), msg("m2")))
        assertTrue(players.single().released)
        assertEquals(VideoStatus.DELETED, state.now.value?.status)
        assertEquals(Messages.MESSAGE_DELETED, videoNotice(state.now.value!!.status))
        state.release(a) // the bubble goes; the full screen still says why until it is closed
        assertEquals(VideoStatus.DELETED, state.now.value?.status)
        state.closeFullScreen()
        assertNull(state.now.value)
        state.toggle() // nothing to play
        assertEquals(1, players.size)
    }

    @Test fun streamErrorShowsRetry() {
        state.play(a)
        val p = players.single()
        p.emit(VideoPhase.READY)
        p.positionMs = 16_000
        p.emit(VideoPhase.BUFFERING) // the network dropped: ExoPlayer buffers and retries
        assertEquals(VideoStatus.BUFFERING, state.now.value?.status)
        p.emit(VideoPhase.READY) // back
        assertEquals(VideoStatus.PLAYING, state.now.value?.status)
        p.emit(VideoPhase.BUFFERING)
        p.failNow() // its retry budget is spent (or 401 / 404)
        assertEquals(VideoStatus.UNAVAILABLE, state.now.value?.status)
        assertEquals(Messages.VIDEO_UNAVAILABLE, videoNotice(VideoStatus.UNAVAILABLE))
        assertEquals(16_000, state.now.value?.positionMs)
        state.retry()
        assertEquals(listOf("f1" to 0L, "f1" to 16_000L), p.loads) // from where it stopped
        assertTrue(p.playing); assertEquals(VideoStatus.LOADING, state.now.value?.status)
        assertNull(videoNotice(VideoStatus.LOADING))
    }

    @Test fun fullScreenKeepsPosition() {
        state.play(a)
        val p = players.single()
        p.emit(VideoPhase.READY)
        p.positionMs = 16_000; p.durationMs = 42_000
        state.tick()
        state.openFullScreen(a)
        assertTrue(state.now.value!!.fullScreen)
        assertSame(p, state.player) // the same player moves to the full screen: nothing reloads
        assertEquals(1, p.loads.size)
        assertEquals("0:16 / 0:42", Messages.videoTime(state.now.value!!.positionMs, state.now.value!!.durationMs!!))
        state.skip(10_000); assertEquals(26_000, p.positionMs)
        state.skip(-30_000); assertEquals(0, p.positionMs)
        state.skip(60_000); assertEquals(42_000, p.positionMs)
        state.seek(16_000)
        state.closeFullScreen()
        assertFalse(state.now.value!!.fullScreen)
        assertEquals(16_000, state.now.value!!.positionMs)
        assertEquals(1, p.loads.size); assertTrue(p.playing)
        state.openFullScreen(b) // only the video that is playing can go full screen
        assertFalse(state.now.value!!.fullScreen)
    }

    @Test fun endedPlaysAgainFromTheStart() {
        state.play(a)
        val p = players.single()
        p.durationMs = 42_000; p.positionMs = 42_000
        p.playing = false
        p.emit(VideoPhase.ENDED)
        assertEquals(VideoStatus.ENDED, state.now.value?.status)
        state.toggle()
        assertEquals(0L, p.seeks.last()); assertTrue(p.playing)
    }
}
