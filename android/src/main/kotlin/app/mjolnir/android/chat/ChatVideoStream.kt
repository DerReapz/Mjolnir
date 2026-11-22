package app.mjolnir.android.chat

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.mjolnir.android.ui.VideoPhase
import app.mjolnir.android.ui.VideoPlayer
import app.mjolnir.android.ui.VideoPlayerListener
import app.mjolnir.client.RelayApi
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** The host of [chatFileUri]: a stand-in that [ChatFileAuth] always replaces with the relay's address. */
private const val CHAT_FILE_HOST = "chat-file.invalid"

/**
 * The address the player is given for chat file [fileId]. The real one (relay address and token) is only looked up by
 * [ChatFileAuth] on the loading thread, for every request, since reading the token may touch the disk.
 */
fun chatFileUri(fileId: String): String = "http://$CHAT_FILE_HOST/$fileId"

/**
 * Turns each [chatFileUri] request into [RelayApi.chatFileRequest]: the relay's `GET /v1/files/{id}` with
 * `Authorization: Bearer {token}`. The player's own headers (its `Range: bytes=N-`) stay. Anything that fails while
 * building the request is an [IOException], which the player treats as a network error; nothing else escapes onto
 * OkHttp's threads.
 */
class ChatFileAuth(private val api: RelayApi) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.url.host != CHAT_FILE_HOST) return chain.proceed(original)
        val fileId = original.url.pathSegments.lastOrNull().orEmpty()
        val request = try {
            val target = api.chatFileRequest(fileId)
            original.newBuilder().url(target.url).apply { target.headers.forEach { (name, value) -> header(name, value) } }.build()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("couldn't build the request for chat file $fileId", e)
        }
        return chain.proceed(request)
    }
}

/** Network errors get this many tries (about 15 s of backoff) before the video shows `Video unavailable`. */
private const val NETWORK_RETRIES = 6

/** As ExoPlayer's default, but a refused token (401/403) or a deleted file (404/410) fails at once: retrying can't help. */
@OptIn(UnstableApi::class)
internal class ChatVideoErrorPolicy : DefaultLoadErrorHandlingPolicy(NETWORK_RETRIES) {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val code = (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return if (code == 401 || code == 403 || code == 404 || code == 410) C.TIME_UNSET else super.getRetryDelayMsFor(info)
    }
}

/** The app's HTTP client for chat videos (one connection pool for every player). */
fun chatVideoHttpClient(api: RelayApi): OkHttpClient = OkHttpClient.Builder().addInterceptor(ChatFileAuth(api)).build()

/** [VideoPlayer] on ExoPlayer, streaming chat files over [http] (built with [chatVideoHttpClient]). Main thread only. */
@OptIn(UnstableApi::class)
class ExoVideoPlayer(context: Context, http: OkHttpClient) : VideoPlayer {
    private val sources = ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(http)).setLoadErrorHandlingPolicy(ChatVideoErrorPolicy())

    /** The ExoPlayer itself, for the PlayerView that shows its picture. */
    val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        // Takes audio focus (other audio pauses, a call pauses the video) and pauses when headphones are unplugged.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    private var listener: VideoPlayerListener? = null

    private val events = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = report()
        override fun onIsPlayingChanged(isPlaying: Boolean) = report()
        override fun onPlayerError(error: PlaybackException) { listener?.onError() }
    }

    init { exo.addListener(events) }

    private fun report() {
        val phase = when (exo.playbackState) {
            Player.STATE_READY -> VideoPhase.READY
            Player.STATE_ENDED -> VideoPhase.ENDED
            Player.STATE_BUFFERING -> VideoPhase.BUFFERING
            else -> return // idle: before prepare, or after an error (reported by onPlayerError)
        }
        listener?.onPlayback(phase, exo.isPlaying)
    }

    override fun load(fileId: String, startMs: Long) {
        exo.setMediaSource(sources.createMediaSource(MediaItem.fromUri(chatFileUri(fileId))), startMs)
        exo.prepare()
    }

    override fun play() {
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
    }

    override fun pause() = exo.pause()
    override fun seek(ms: Long) = exo.seekTo(ms)
    override val positionMs: Long get() = exo.currentPosition.coerceAtLeast(0)
    override val durationMs: Long? get() = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
    override fun setListener(listener: VideoPlayerListener?) { this.listener = listener }

    override fun release() {
        exo.removeListener(events)
        exo.release()
    }
}
