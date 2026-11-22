package app.mjolnir.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.AttachmentGroup
import app.mjolnir.client.Messages
import app.mjolnir.client.PendingFile
import app.mjolnir.client.PendingSend
import app.mjolnir.client.groupAttachments
import app.mjolnir.client.groupPendingFiles
import app.mjolnir.client.validDurationMs
import app.mjolnir.client.validSize
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.ChatMessage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the bubbles get an attachment's files: the app's media cache, downloading on demand. */
interface ChatMediaSource {
    /** The attachment's thumbnail, or null when it has none. Throws when it can't be fetched. */
    suspend fun thumbnail(att: Attachment): File?
    /** The attachment's file. Throws when it can't be fetched (e.g. its message was deleted). */
    suspend fun file(att: Attachment): File
    /** A video's file, as [file], reporting (bytes so far, total) while it downloads. */
    suspend fun video(att: Attachment, onProgress: (Long, Long) -> Unit): File = file(att)
    /** Keeps [fileId] in the cache while a bubble shows it; matched by [unpin]. */
    fun pin(fileId: String) {}
    fun unpin(fileId: String) {}
    /** Changes each time the relay connection comes up: media that failed to load (offline) tries again. */
    val connects: StateFlow<Int> get() = NO_CONNECTS
}

private val NO_CONNECTS: StateFlow<Int> = MutableStateFlow(0)

/** No media at all: bubbles show their placeholders. */
object NoChatMedia : ChatMediaSource {
    override suspend fun thumbnail(att: Attachment): File? = null
    override suspend fun file(att: Attachment): File = throw java.io.FileNotFoundException(att.name)
}

/** Inline photos and GIFs are at most this wide (and tall). */
internal val MEDIA_MAX = 320.dp
private const val MEDIA_MAX_PX = 320
/** Inline bubbles fetch the full photo or GIF only up to this size; bigger ones stay on their thumbnail until opened. */
private const val INLINE_FULL_MAX_BYTES = 16L shl 20
/** Skia decodes unscaled, so formats ImageIO can't read (WebP) are decoded only up to this many pixels (real size, from the header)… */
internal const val SKIA_MAX_PIXELS = 25_000_000L
/** …and only from files up to this size, as they are read whole. The viewer reads a GIF whole too, up to this. */
private const val READ_WHOLE_MAX_BYTES = 32L shl 20
/** The viewer decodes at most this long a side, and never more than the screen. */
private const val VIEWER_MAX_PX = 4096

private val PLACEHOLDER = Color(0xFF2A2418)
private val TAG_FILL = Color(0xFF262626)
private val mediaShape = RoundedCornerShape(12.dp)

private fun body(size: Int, weight: FontWeight? = null, color: Color = Forge.text, family: FontFamily = ForgeFonts.inter400) =
    TextStyle(fontFamily = family, fontSize = size.sp, color = color, fontWeight = weight)

/**
 * The picture in [file] scaled to fit [maxPx]: ImageIO, subsampled (kept in the thumbnail cache when [cached]), else
 * Skia for formats ImageIO lacks (WebP). Null if neither reads it or it is too big to decode safely.
 */
internal fun decodeImage(file: File, maxPx: Int, cached: Boolean = true): ImageBitmap? =
    (if (cached) loadThumbnail(file.path, maxPx) else decodeUncached(file, maxPx)) ?: decodeWithSkia(file, maxPx)

/** [file] via Skia, scaled down to fit [maxPx]; null over [SKIA_MAX_PIXELS] (its header's real size) or [READ_WHOLE_MAX_BYTES]. */
internal fun decodeWithSkia(file: File, maxPx: Int): ImageBitmap? = try {
    if (file.length() > READ_WHOLE_MAX_BYTES) null else {
        val bytes = file.readBytes()
        val (w, h) = org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)).use { it.width to it.height } // the header only
        if (w <= 0 || h <= 0 || w.toLong() * h > SKIA_MAX_PIXELS) null
        else org.jetbrains.skia.Image.makeFromEncoded(bytes).use { image ->
            val scale = minOf(1f, maxPx.toFloat() / maxOf(w, h))
            if (scale >= 1f) image.toComposeImageBitmap()
            else {
                val tw = (w * scale).toInt().coerceAtLeast(1); val th = (h * scale).toInt().coerceAtLeast(1)
                org.jetbrains.skia.Surface.makeRasterN32Premul(tw, th).use { surface ->
                    surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(tw.toFloat(), th.toFloat()))
                    surface.makeImageSnapshot().toComposeImageBitmap()
                }
            }
        }
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/** [file]'s bytes, or null when it is over [max] bytes (checked before reading). */
private fun readUpTo(file: File, max: Long): ByteArray? = if (file.length() > max) null else file.readBytes()

/** The viewer's decode size: the screen's long side, at most [VIEWER_MAX_PX]. */
private fun viewerMaxPx(): Int = try {
    java.awt.Toolkit.getDefaultToolkit().screenSize.let { maxOf(it.width, it.height) }.coerceIn(MEDIA_MAX_PX, VIEWER_MAX_PX)
} catch (_: Exception) {
    2560 // headless
}

/** Runs [block], turning a failure (not a cancellation) into null: a bubble that can't fetch its media keeps its placeholder. */
private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/** Keeps [ids] pinned in the media cache while this is in composition. */
@Composable
private fun PinWhileShown(media: ChatMediaSource, vararg ids: String?) {
    val keys = ids.filterNotNull()
    DisposableEffect(media, keys) {
        keys.forEach(media::pin)
        onDispose { keys.forEach(media::unpin) }
    }
}

/** The size a picture of [w]×[h] shows at inline: as is, scaled down to fit [MEDIA_MAX] square. */
private fun inlineSize(w: Int?, h: Int?): Pair<Dp, Dp> {
    if (w == null || h == null || w <= 0 || h <= 0) return 240.dp to 150.dp // the mockup's photo placeholder
    val scale = minOf(1f, MEDIA_MAX_PX.toFloat() / w, MEDIA_MAX_PX.toFloat() / h)
    return (w * scale).coerceAtLeast(48f).dp to (h * scale).coerceAtLeast(32f).dp
}

/** A message's attachments: photos and GIFs as pictures, files and folders as file bubbles; in sent order. */
@Composable
internal fun MessageAttachments(
    m: ChatMessage, mine: Boolean, media: ChatMediaSource, playing: Boolean,
    onOpen: (Attachment) -> Unit, onDownload: (List<Attachment>) -> Unit, video: VideoSupport = NoVideo,
) {
    val groups = remember(m.attachments) { groupAttachments(m.attachments) }
    for (group in groups) {
        when (group) {
            is AttachmentGroup.Folder -> FolderBubble(group.name, group.count, group.totalBytes, mine) { onDownload(group.items) }
            is AttachmentGroup.Single -> {
                val att = group.item
                when (att.kind) {
                    AttachmentKind.IMAGE -> ImageBubble(att, media) { onOpen(att) }
                    AttachmentKind.GIF -> GifBubble(att, media, playing) { onOpen(att) }
                    AttachmentKind.VIDEO -> VideoBubble(att, media, video, playing)
                    AttachmentKind.FILE -> FileBubble(att.kind, att.name, att.sizeBytes, mine) { onDownload(listOf(att)) }
                }
            }
        }
    }
}

/** A photo: its thumbnail first, then the full picture once fetched. Clicking opens the viewer. */
@Composable
private fun ImageBubble(att: Attachment, media: ChatMediaSource, onOpen: () -> Unit) {
    PinWhileShown(media, att.fileId, att.thumbFileId)
    var thumb by remember(att.fileId) { mutableStateOf<ImageBitmap?>(null) }
    var full by remember(att.fileId) { mutableStateOf<ImageBitmap?>(null) }
    val connects by media.connects.collectAsState()
    LaunchedEffect(att.fileId, connects) { // what failed offline loads again once the relay is back
        if (thumb == null) thumb = orNull { media.thumbnail(att)?.let { withContext(Dispatchers.IO) { decodeImage(it, MEDIA_MAX_PX) } } }
        if (full == null && att.sizeBytes <= INLINE_FULL_MAX_BYTES) {
            full = orNull { media.file(att).let { withContext(Dispatchers.IO) { decodeImage(it, MEDIA_MAX_PX * 2) } } }
        }
    }
    val shown = full ?: thumb
    val (w, h) = inlineSize(att.width ?: shown?.width, att.height ?: shown?.height)
    Box(
        Modifier.size(w, h).clip(mediaShape).background(PLACEHOLDER, mediaShape).border(1.dp, Forge.border, mediaShape)
            .semantics { contentDescription = "Photo ${att.name}" }.clickable(role = Role.Button, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(shown, null, Modifier.fillMaxSize().testTag(if (full != null) "image-full" else "image-thumb"), contentScale = ContentScale.Crop)
        } else {
            Text(Messages.mediaLabel(att.kind), style = body(12, color = Forge.muted))
        }
    }
}

/** A GIF: its first frame (the thumbnail) at once, then animating once fetched; the `GIF` badge in its corner. */
@Composable
private fun GifBubble(att: Attachment, media: ChatMediaSource, playing: Boolean, onOpen: () -> Unit) {
    PinWhileShown(media, att.fileId, att.thumbFileId)
    var still by remember(att.fileId) { mutableStateOf<ImageBitmap?>(null) }
    var gif by remember(att.fileId) { mutableStateOf<GifFrames?>(null) }
    val connects by media.connects.collectAsState()
    LaunchedEffect(att.fileId, connects) { // what failed offline loads again once the relay is back
        if (still == null) still = orNull { media.thumbnail(att)?.let { withContext(Dispatchers.IO) { decodeImage(it, MEDIA_MAX_PX) } } }
        if (gif == null && att.sizeBytes <= INLINE_FULL_MAX_BYTES) {
            gif = orNull { media.file(att).let { f -> withContext(Dispatchers.IO) { readUpTo(f, INLINE_FULL_MAX_BYTES)?.let { GifFrames.decode(it) } } } }
        }
    }
    val (w, h) = inlineSize(att.width ?: gif?.width ?: still?.width, att.height ?: gif?.height ?: still?.height)
    Box(
        Modifier.size(w, h).clip(mediaShape).background(TAG_FILL, mediaShape).border(1.dp, Forge.border, mediaShape)
            .semantics { contentDescription = "GIF ${att.name}" }.clickable(role = Role.Button, onClick = onOpen),
    ) {
        val frames = gif
        if (frames != null) AnimatedGif(frames, null, Modifier.fillMaxSize().testTag("gif-animated"), playing)
        else still?.let { Image(it, null, Modifier.fillMaxSize().testTag("gif-still"), contentScale = ContentScale.Crop) }
        GifBadge(Modifier.align(Alignment.BottomStart).padding(6.dp))
    }
}

@Composable
private fun GifBadge(modifier: Modifier = Modifier) = Text(
    "GIF", style = body(10, FontWeight.Bold, Forge.onAccent, ForgeFonts.inter600),
    modifier = modifier.background(Forge.text, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
)

/** Where a video bubble is: its still, fetching the file, playing inline, or unplayable here. */
private sealed interface VideoPhase {
    data object Still : VideoPhase
    /** Downloading into the cache: [fraction] done. */
    data class Fetching(val fraction: Float) : VideoPhase
    data class Playing(val engine: VideoEngine, val file: File) : VideoPhase
    /** JavaFX can't decode it: the system player can try ([file] is the cached copy). */
    data class Unplayable(val file: File) : VideoPhase
    /** The file couldn't be fetched (offline, or its message was deleted); clicking tries again. */
    data object Unavailable : VideoPhase
}

/** What a video bubble holds that must be let go when it leaves the screen: the engine and the cache pin. */
private class VideoHold(val media: ChatMediaSource, val fileId: String) {
    var engine: VideoEngine? = null
    private var pinned = false
    fun pin() { if (!pinned) { pinned = true; media.pin(fileId) } }
    /** Never throws: it runs from catch blocks and from onDispose. */
    fun releaseEngine() {
        val e = engine ?: return
        engine = null
        NowPlaying.stopped(e)
        runCatching { e.release() }
    }
    fun releaseAll() { releaseEngine(); if (pinned) { pinned = false; media.unpin(fileId) } }
}

/**
 * A video: its thumbnail with a play button and a `0:42` badge (a plain tile when it has neither), 320 px wide with its
 * aspect kept. Play downloads it first (a ring on the button shows how far), then plays it inline; it stays pinned in
 * the cache while it plays. Leaving the screen (scrolled away, another chat, the message deleted) releases the player;
 * a hidden window pauses it. While an overlay covers the conversation or the bubble is not wholly inside the list, it is
 * paused and shows its still instead of the player, whose Swing panel would draw over everything (ruling CV-R2). A video
 * JavaFX can't decode offers the system player when its name is a video's.
 */
@Composable
private fun VideoBubble(att: Attachment, media: ChatMediaSource, video: VideoSupport, showing: Boolean) {
    PinWhileShown(media, att.thumbFileId)
    var thumb by remember(att.fileId) { mutableStateOf<ImageBitmap?>(null) }
    var phase by remember(att.fileId) { mutableStateOf<VideoPhase>(VideoPhase.Still) }
    val hold = remember(att.fileId) { VideoHold(media, att.fileId) }
    DisposableEffect(hold) { onDispose { hold.releaseAll() } }
    val connects by media.connects.collectAsState()
    LaunchedEffect(att.fileId, connects) {
        if (thumb == null) thumb = orNull { media.thumbnail(att)?.let { withContext(Dispatchers.IO) { decodeImage(it, MEDIA_MAX_PX) } } }
    }
    val stage = LocalVideoStage.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val inView = !stage.covered && stage.viewport.value.let { v -> v == null || bounds.let { b -> b == null || v.encloses(b) } }
    val current = phase
    val requester = remember { BringIntoViewRequester() }
    if (current is VideoPhase.Playing) {
        val error by current.engine.error.collectAsState()
        val playing by current.engine.playing.collectAsState()
        LaunchedEffect(error) { if (error != null) { hold.releaseEngine(); phase = VideoPhase.Unplayable(current.file) } }
        // Not while it is being scrolled into view (below): it starts there partly hidden by its own controls.
        var settled by remember(current.engine) { mutableStateOf(false) }
        LaunchedEffect(current.engine) { withFrameNanos {}; runCatching { requester.bringIntoView() }; settled = true }
        LaunchedEffect(showing, inView, playing, settled) { if (playing && (!showing || (settled && !inView))) current.engine.pause() }
    }
    val scope = rememberCoroutineScope()
    val start: () -> Unit = {
        scope.launch {
            hold.pin()
            phase = VideoPhase.Fetching(0f)
            val file = try {
                media.video(att) { done, total -> phase = VideoPhase.Fetching(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                phase = VideoPhase.Unavailable
                return@launch
            }
            val engine = try {
                video.engine().also { hold.engine = it; withContext(Dispatchers.IO) { it.load(file) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { // MediaException and the like: an unsupported codec, or no JavaFX here
                hold.releaseEngine(); phase = VideoPhase.Unplayable(file); return@launch
            } catch (_: LinkageError) {
                hold.releaseEngine(); phase = VideoPhase.Unplayable(file); return@launch
            }
            NowPlaying.start(engine)
            engine.play()
            phase = VideoPhase.Playing(engine, file)
        }
    }
    val size = att.validSize() ?: thumb?.let { it.width to it.height }
    val (w, h) = videoSize(size)
    if (current is VideoPhase.Playing) {
        // The player is taller than the still (its controls): it is scrolled wholly into view (above) as it starts.
        Box(Modifier.bringIntoViewRequester(requester).clip(mediaShape).border(1.dp, Forge.border, mediaShape).trackBounds { bounds = it }) {
            InlineVideoPlayer(current.engine, video, w, h, att.name, showPicture = inView) {
                Box(Modifier.fillMaxSize().background(VIDEO_FRAME).testTag("video-cover")) {
                    thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
        }
        return
    }
    val length = validDurationMs(att.durationMs)
    val clickable = current is VideoPhase.Still || current is VideoPhase.Unavailable
    Box(
        Modifier.trackBounds { bounds = it }.size(w, h).clip(mediaShape).background(VIDEO_FRAME, mediaShape).border(1.dp, Forge.border, mediaShape)
            .semantics { contentDescription = "Video ${att.name}" }
            .then(if (clickable) Modifier.clickable(role = Role.Button, onClick = start) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            Image(it, null, Modifier.fillMaxSize().testTag("video-thumb").alpha(if (current is VideoPhase.Unplayable) 0.35f else 1f), contentScale = ContentScale.Crop)
        }
        when (current) {
            is VideoPhase.Unplayable -> Column(
                Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(Messages.CANT_PLAY_VIDEO, style = body(13, color = Forge.text))
                // Not for a name the system would run rather than play (ruling CV-R4): Download stays in ⋯.
                if (playerFileName(att.name) != null) ChatButton(Messages.OPEN_IN_PLAYER, { video.openInPlayer(att, current.file) })
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayButton(current as? VideoPhase.Fetching)
                    if (current is VideoPhase.Unavailable) Text(Messages.VIDEO_UNAVAILABLE, style = body(12, color = Forge.text))
                }
                if (length != null && thumb != null) DurationBadge(length, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
    }
}

/** The round play button; while [fetching], a ring around it fills as the video downloads. */
@Composable
private fun PlayButton(fetching: VideoPhase.Fetching?) {
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (fetching != null) {
            CircularProgressIndicator(
                progress = { fetching.fraction }, color = Forge.accent, trackColor = Color.Black.copy(alpha = 0.5f), strokeWidth = 3.dp,
                modifier = Modifier.size(56.dp).semantics { contentDescription = "Downloading video" },
            )
        }
        Box(
            Modifier.size(44.dp).background(Forge.accent.copy(alpha = 0.92f), CircleShape).semantics { contentDescription = "Play" },
            contentAlignment = Alignment.Center,
        ) { PlayGlyph(Forge.onAccent, 16.dp) }
    }
}

@Composable
private fun DurationBadge(ms: Long, modifier: Modifier = Modifier) = Text(
    Messages.duration(ms), style = body(11, FontWeight.SemiBold, Color.White, ForgeFonts.inter600),
    modifier = modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
)

/** The file type tag of a file bubble: its extension ("PDF"), else "FILE"; "VIDEO" for a video. */
internal fun fileTag(kind: AttachmentKind, name: String): String =
    if (kind == AttachmentKind.VIDEO) "VIDEO"
    else name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 4 && it != name }?.uppercase() ?: "FILE"

/** A file (or a video still uploading): its type tag, its name, and `File · 220 KB` / `Video · 18 MB` (ruling CM-R4). Clicking downloads it. */
@Composable
private fun FileBubble(kind: AttachmentKind, name: String, bytes: Long, mine: Boolean, onDownload: (() -> Unit)?) =
    FileLikeBubble(fileTag(kind, name), name, Messages.fileBubbleDetail(kind, bytes), name, mine, onDownload)

/** A sent folder as one bubble: its name and `Folder · {n} files · {size}`. Clicking downloads all of it. */
@Composable
private fun FolderBubble(name: String, count: Int, bytes: Long, mine: Boolean, onDownload: (() -> Unit)?) =
    FileLikeBubble(null, name, Messages.folderDetail(count, bytes), name, mine, onDownload)

@Composable
private fun FileLikeBubble(tag: String?, title: String, detail: String, name: String, mine: Boolean, onDownload: (() -> Unit)?) {
    val base = if (mine) Modifier.background(MINE_FILL, mediaShape).border(1.dp, MINE_BORDER, mediaShape) else Modifier.background(Forge.surface, mediaShape)
    val click = if (onDownload != null) Modifier.semantics { contentDescription = "Download $name" }.clickable(role = Role.Button, onClick = onDownload) else Modifier
    Row(
        Modifier.widthIn(max = MEDIA_MAX).clip(mediaShape).then(base).then(click).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).background(TAG_FILL, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            if (tag != null) Text(tag, style = body(10, FontWeight.SemiBold, Forge.muted, ForgeFonts.inter600), maxLines = 1)
            else ForgeIcon(ICON_FOLDER, Forge.muted, 18.dp)
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, style = body(14, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = body(12, color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- sends not posted yet ----

/** "400 KB of 1 MB · 40%". */
internal fun sendProgress(done: Long, total: Long): String {
    val fraction = if (total > 0) done.coerceIn(0, total).toFloat() / total else 1f
    return "${Messages.size(done)} of ${Messages.size(total)} · ${(fraction * 100).toInt()}%"
}

/**
 * A send still uploading: its files and text greyed (text last, as the posted message shows it), with a progress bar and
 * *Cancel*. One that failed for good shows why and offers *Dismiss*.
 */
@Composable
internal fun PendingSendBubbles(send: PendingSend, onDismiss: () -> Unit) {
    val failed = send.failure != null
    Column(
        Modifier.fillMaxWidth().testTag("pending-${send.id}"), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            Modifier.alpha(0.5f).semantics { contentDescription = if (failed) "Not sent" else "Sending" },
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (group in remember(send.files) { groupPendingFiles(send.files) }) {
                when (group) {
                    is AttachmentGroup.Folder -> FolderBubble(group.name, group.count, group.totalBytes, mine = true, onDownload = null)
                    is AttachmentGroup.Single -> PendingFileBubble(group.item)
                }
            }
            send.text?.takeIf { it.isNotBlank() }?.let { text ->
                val shape = RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp)
                Text(text, style = body(14).copy(lineHeight = 20.sp), modifier = Modifier.widthIn(max = 480.dp)
                    .background(MINE_FILL, shape).border(1.dp, MINE_BORDER, shape).padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(send.failure!!, style = body(12, color = Forge.error))
                ForgeTextButton(Messages.DISMISS, onClick = onDismiss, muted = true)
            }
        } else {
            val fraction = if (send.totalBytes > 0) send.sentBytes.coerceIn(0, send.totalBytes).toFloat() / send.totalBytes else 0f
            Box(
                Modifier.width(160.dp).height(4.dp).background(Forge.border, RoundedCornerShape(2.dp))
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
            ) { Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Forge.accent, RoundedCornerShape(2.dp))) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sendProgress(send.sentBytes, send.totalBytes), style = body(11, color = Forge.muted))
                ForgeTextButton(Messages.CANCEL, onClick = onDismiss, muted = true)
            }
        }
    }
}

/** A queued photo or GIF from its local thumbnail (or the file itself); anything else as a file bubble. */
@Composable
private fun PendingFileBubble(f: PendingFile) {
    if (f.kind == AttachmentKind.IMAGE || f.kind == AttachmentKind.GIF) {
        val bitmap = rememberThumbnail(f.thumbPath?.takeIf { File(it).exists() } ?: f.path, MEDIA_MAX_PX)
        val (w, h) = inlineSize(bitmap?.width, bitmap?.height)
        Box(Modifier.size(w, h).clip(mediaShape).background(PLACEHOLDER, mediaShape).border(1.dp, Forge.border, mediaShape)) {
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (f.kind == AttachmentKind.GIF) GifBadge(Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
    } else {
        FileBubble(f.kind, f.name, f.sizeBytes, mine = true, onDownload = null)
    }
}

// ---- the viewer ----

/** A photo or GIF at full size over the conversation, with *Download* and *Close*. */
@Composable
internal fun ImageViewer(att: Attachment, media: ChatMediaSource, playing: Boolean, onDownload: () -> Unit, onClose: () -> Unit) {
    PinWhileShown(media, att.fileId, att.thumbFileId)
    var still by remember(att.fileId) { mutableStateOf<ImageBitmap?>(null) }
    var gif by remember(att.fileId) { mutableStateOf<GifFrames?>(null) }
    var failed by remember(att.fileId) { mutableStateOf(false) }
    LaunchedEffect(att.fileId) {
        still = orNull { media.thumbnail(att)?.let { withContext(Dispatchers.IO) { decodeImage(it, MEDIA_MAX_PX) } } }
        val file = orNull { media.file(att) }
        if (file == null) { failed = true; return@LaunchedEffect }
        if (att.kind == AttachmentKind.GIF) gif = orNull { withContext(Dispatchers.IO) { readUpTo(file, READ_WHOLE_MAX_BYTES)?.let { GifFrames.decode(it) } } }
        // Decoded at screen size and not cached: one big picture, dropped when the viewer closes.
        if (gif == null) still = orNull { withContext(Dispatchers.IO) { decodeImage(file, viewerMaxPx(), cached = false) } } ?: still
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).semantics { contentDescription = "Image viewer" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val frames = gif
                when {
                    frames != null -> AnimatedGif(frames, att.name, Modifier.fillMaxSize(), playing)
                    still != null -> Image(still!!, att.name, Modifier.fillMaxSize().testTag("viewer-image"), contentScale = ContentScale.Fit)
                    else -> Text(if (failed) "Couldn't load ${att.name}" else "Loading…", style = body(13, color = Forge.muted))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Messages.attachmentDetail(att.name, att.sizeBytes), Modifier.weight(1f), style = body(13, color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChatButton(Messages.DOWNLOAD, onDownload)
                ChatButton("Close", onClose, primary = true)
            }
        }
    }
}
