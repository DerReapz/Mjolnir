package app.mjolnir.android.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import app.mjolnir.R
import app.mjolnir.android.chat.ExoVideoPlayer
import app.mjolnir.client.Messages
import app.mjolnir.client.validDurationMs
import app.mjolnir.client.validSize
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val VIDEO_FRAME = Color(0xFF0E0E0E)
private val SCRIM = Color(0xB3141414) // the mockup's rgba(20,20,20,0.7)
/** How often a showing video's time text and scrub bar move on. */
internal const val VIDEO_TICK_MS = 250L

private val FullScreenIcon = strokeIcon("fullscreen", "M4 9V4h5", "M15 4h5v5", "M20 15v5h-5", "M9 20H4v-5")
internal val PlayIcon: ImageVector = ImageVector.Builder("play", 20.dp, 20.dp, 24f, 24f)
    .apply { addPath(addPathNodes("M8 5v14l11-7z"), fill = SolidColor(Color.Black)) }.build()

/** Whether [status] means the player holds a picture (or is getting one) for this video. */
private fun showsPlayer(status: VideoStatus) = status != VideoStatus.UNAVAILABLE && status != VideoStatus.DELETED

/**
 * A video per the `PhoneChat` mockup: its thumbnail (or a plain tile without one, ruling CV-P4) with a play button and
 * the length in its corner. A tap streams it inline in the app's one player ([videos]); while it plays, a tap pauses
 * it and the corner button opens it full screen. A long press opens the message's sheet (Download, Share…).
 */
@Composable
internal fun VideoBubble(m: ChatMessage, att: Attachment, media: ChatMediaSource?, videos: VideoPlayerState, onLongPress: (() -> Unit)?) {
    val key = remember(m.id, att.fileId) { VideoKey(m.id, att.fileId) }
    PinWhileShown(media, att.thumbFileId)
    val maxPx = inlineMaxPx()
    var thumb by remember(att.fileId) { mutableStateOf<Bitmap?>(null) }
    val connects by (media?.connects ?: NO_CONNECTS).collectAsState()
    LaunchedEffect(att.fileId, media, connects) {
        if (media != null && thumb == null) thumb = orNull { media.thumbnail(att)?.let { withContext(Dispatchers.IO) { inlineBitmap(it, maxPx) } } }
    }
    // Leaving the screen (scrolled away, the chat closed, the message deleted) frees the player; a rotation keeps it playing.
    val activity = LocalActivity.current
    DisposableEffect(key, videos) {
        onDispose { if (activity?.isChangingConfigurations != true) videos.release(key) }
    }
    val now by videos.now.collectAsState()
    val ui = now?.takeIf { it.key == key && it.status != VideoStatus.DELETED }
    val inline = ui != null && !ui.fullScreen
    if (inline) LaunchedEffect(key) { while (true) { videos.tick(); delay(VIDEO_TICK_MS) } }
    val playing = ui?.status == VideoStatus.PLAYING || ui?.status == VideoStatus.BUFFERING || ui?.status == VideoStatus.LOADING

    BoxWithConstraints {
        val (w, h) = inlineVideoSize(att.validSize() ?: thumb?.let { it.width to it.height }, maxWidth)
        Box(
            Modifier.size(w, h).clip(mediaShape).background(VIDEO_FRAME, mediaShape).border(1.dp, Forge.Border, mediaShape)
                // No sound from a bubble that left the screen: nothing of it shows any more, so it pauses.
                .onGloballyPositioned { if (inline && playing && it.boundsInWindow().isEmpty) videos.pause() }
                .tapOrHold(
                    "Video ${att.name}",
                    onTap = when {
                        ui == null -> ({ videos.play(key) })
                        ui.fullScreen || !showsPlayer(ui.status) -> null
                        else -> ({ videos.toggle() })
                    },
                    onLongPress = onLongPress,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val still = thumb
            val loading = ui?.status == VideoStatus.LOADING
            if (still != null && (ui == null || ui.fullScreen || loading || !showsPlayer(ui.status))) {
                Image(
                    still.asImageBitmap(), null, Modifier.fillMaxSize().alpha(if (ui != null && !showsPlayer(ui.status)) 0.35f else 1f),
                    contentScale = ContentScale.Crop,
                )
            }
            when {
                ui == null || ui.fullScreen -> {
                    PlayBadge(52.dp)
                    val length = validDurationMs(att.durationMs)
                    if (length != null && still != null) DurationBadge(length, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
                !showsPlayer(ui.status) -> Unavailable(onRetry = videos::retry)
                else -> {
                    VideoPicture(videos, Modifier.fillMaxSize())
                    InlineControls(ui, onFullScreen = { videos.openFullScreen(key) })
                }
            }
        }
    }
}

/** The picture of the app's player (when it is the ExoPlayer one), in a PlayerView. */
@Composable
internal fun VideoPicture(videos: VideoPlayerState, modifier: Modifier) {
    val exo = (videos.player as? ExoVideoPlayer)?.exo
    AndroidView(
        // AndroidView gives no parent to inflate into; the PlayerView fills whatever box Compose gives it.
        factory = { LayoutInflater.from(it).inflate(R.layout.chat_video_view, FrameLayout(it), false) as PlayerView },
        update = { if (it.player !== exo) it.player = exo },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/** While the video shows inline: a spinner while it fetches, the play button while paused, the time and the full-screen button. */
@Composable
private fun InlineControls(ui: VideoUi, onFullScreen: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        when (ui.status) {
            VideoStatus.LOADING, VideoStatus.BUFFERING -> Spinner(Modifier.align(Alignment.Center))
            VideoStatus.PAUSED, VideoStatus.ENDED -> PlayBadge(52.dp, Modifier.align(Alignment.Center))
            else -> {}
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ui.durationMs?.let { d ->
                Text(
                    Messages.videoTime(ui.positionMs, d), color = Forge.Text, fontSize = 11.sp,
                    modifier = Modifier.background(SCRIM, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Box(Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button, onClick = onFullScreen).semantics { contentDescription = "Full screen" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(30.dp).background(SCRIM, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(FullScreenIcon, contentDescription = null, tint = Forge.Text, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** `Video unavailable` (deleted, access lost, the network gone past its retries, or a codec the phone lacks) with *Retry*. */
@Composable
internal fun Unavailable(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Messages.VIDEO_UNAVAILABLE, color = Forge.Text, fontSize = 13.sp)
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(Messages.RETRY) }
    }
}

/** The mockup's round play button: a dark disc with a light ring. Decorative: the bubble itself is the button. */
@Composable
internal fun PlayBadge(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(SCRIM, CircleShape).border(2.dp, Forge.Text, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(PlayIcon, contentDescription = null, tint = Forge.Text, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun Spinner(modifier: Modifier = Modifier) = CircularProgressIndicator(
    color = Forge.Accent, strokeWidth = 3.dp, modifier = modifier.size(40.dp).semantics { contentDescription = "Loading video" },
)

@Composable
private fun DurationBadge(ms: Long, modifier: Modifier = Modifier) = Text(
    Messages.duration(ms), color = Forge.Text, fontSize = 11.sp, fontWeight = FontWeight.Medium,
    modifier = modifier.background(SCRIM, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
)
