package app.mjolnir.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mjolnir.client.Messages
import app.mjolnir.protocol.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val CloseIcon = strokeIcon("close", "M6 6l12 12M18 6L6 18")
private val DownloadGlyph = strokeIcon("download", "M12 3v12", "M7 10l5 5 5-5", "M4 21h16")
private const val SKIP_MS = 10_000L

/**
 * The open video full screen, per the `PhoneVideo` mockup: *Close*, who sent it and where, *Download*; the picture from
 * the same player as the bubble (so it carries on where it was); the scrub bar with the time either side; −10 s,
 * play/pause and +10 s. Back or *Close* hands the video back to its bubble.
 */
@Composable
internal fun VideoScreen(
    ui: VideoUi, att: Attachment?, authorName: String, subtitle: String, media: ChatMediaSource?, videos: VideoPlayerState,
    onDownload: () -> Unit, onClose: () -> Unit,
) {
    PinWhileShown(media, att?.thumbFileId)
    val maxPx = inlineMaxPx()
    var thumb by remember(ui.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(ui.key, media) {
        if (media != null && att != null) thumb = orNull { media.thumbnail(att)?.let { withContext(Dispatchers.IO) { inlineBitmap(it, maxPx) } } }
    }
    LaunchedEffect(ui.key) { while (true) { videos.tick(); delay(VIDEO_TICK_MS) } }
    val notice = videoNotice(ui.status)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().semantics { contentDescription = "Video player" }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton44(CloseIcon, "Close", onClose)
                Column(Modifier.weight(1f)) {
                    Text(authorName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = Forge.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (att != null && ui.status != VideoStatus.DELETED) IconButton44(DownloadGlyph, Messages.DOWNLOAD, onDownload)
            }
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .then(if (notice == null) Modifier.clickable(role = Role.Button, onClickLabel = "Play or pause", onClick = videos::toggle) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (notice == null) VideoPicture(videos, Modifier.fillMaxSize())
                val still = thumb
                if (still != null && ui.status == VideoStatus.LOADING) {
                    Image(still.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                when {
                    ui.status == VideoStatus.UNAVAILABLE -> Unavailable(onRetry = videos::retry)
                    notice != null -> Text(notice, color = Forge.Muted, fontSize = 15.sp)
                    ui.status == VideoStatus.LOADING || ui.status == VideoStatus.BUFFERING -> Spinner()
                    else -> {}
                }
            }
            if (notice == null) Controls(ui, videos)
        }
    }
}

/** The scrub bar between the position and the length, then −10 s, play/pause and +10 s. */
@Composable
private fun Controls(ui: VideoUi, videos: VideoPlayerState) {
    val duration = ui.durationMs
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val shownMs = dragging?.let { (it * (duration ?: 0)).toLong() } ?: ui.positionMs
            Text(Messages.duration(shownMs), color = Forge.Muted, fontSize = 12.sp)
            Slider(
                value = dragging ?: if (duration != null && duration > 0) (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { f -> if (duration != null) videos.seek((f * duration).toLong()) }
                    dragging = null
                },
                enabled = duration != null,
                colors = SliderDefaults.colors(thumbColor = Forge.Accent, activeTrackColor = Forge.Accent, inactiveTrackColor = Forge.Border),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Seek" },
            )
            Text(duration?.let(Messages::duration) ?: "–:––", color = Forge.Muted, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            SkipButton("−10", "Back 10 seconds") { videos.skip(-SKIP_MS) }
            val playing = ui.status == VideoStatus.PLAYING || ui.status == VideoStatus.BUFFERING || ui.status == VideoStatus.LOADING
            Box(
                Modifier.size(64.dp).background(Forge.Accent, CircleShape)
                    .clickable(role = Role.Button, onClick = videos::toggle).semantics { contentDescription = if (playing) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) Text("❚❚", color = Forge.OnAccent, fontSize = 18.sp)
                else Icon(PlayIcon, contentDescription = null, tint = Forge.OnAccent, modifier = Modifier.size(24.dp))
            }
            SkipButton("+10", "Forward 10 seconds") { videos.skip(SKIP_MS) }
        }
    }
}

@Composable
private fun SkipButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Forge.Text, fontSize = 13.sp) }
}

@Composable
private fun IconButton44(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Forge.Text, modifier = Modifier.size(22.dp)) }
}
