package app.mjolnir.android.ui

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mjolnir.android.chat.decodeAnimated
import app.mjolnir.android.chat.decodeBitmap
import app.mjolnir.android.chat.viewerPicture
import app.mjolnir.client.AttachmentGroup
import app.mjolnir.client.Messages
import app.mjolnir.client.PendingFile
import app.mjolnir.client.PendingSend
import app.mjolnir.client.groupAttachments
import app.mjolnir.client.groupPendingFiles
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.ChatMessage
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the bubbles get an attachment's files: the app's media cache, downloading on demand. */
interface ChatMediaSource {
    /** The attachment's thumbnail, or null when it has none. Throws when it can't be fetched. */
    suspend fun thumbnail(att: Attachment): File?
    /** The attachment's file. Throws when it can't be fetched (e.g. its message was deleted). */
    suspend fun file(att: Attachment): File
    /** Keeps [fileId] in the cache while a bubble shows it; matched by [unpin]. */
    fun pin(fileId: String)
    fun unpin(fileId: String)
    /** Changes each time the relay connection comes up: media that failed to load (offline) tries again. */
    val connects: StateFlow<Int> get() = NO_CONNECTS
}

internal val NO_CONNECTS: StateFlow<Int> = MutableStateFlow(0)

/** Inline photos and GIFs fit in this square (the mockup's 250 × 150 photo). */
private val MEDIA_MAX = 250.dp
/** Inline bubbles fetch the full photo or GIF only up to this size; bigger ones stay on their thumbnail until opened. */
private const val INLINE_FULL_MAX_BYTES = 16L shl 20
/** The viewer animates GIFs of at most this size. */
private const val VIEWER_GIF_MAX_BYTES = 32L shl 20
/** The viewer decodes at most this long a side, and never more than the screen. */
private const val VIEWER_MAX_PX = 4096

private val PLACEHOLDER = Color(0xFF2A2418)
private val TAG_FILL = Color(0xFF262626)
private val FailureInk = Color(0xFFF87171)
internal val mediaShape = RoundedCornerShape(14.dp)

/**
 * Decoded inline pictures (never larger than a bubble), by file and size, up to 16 MB, so scrolling back doesn't flicker.
 * The viewer's full-screen pictures never go in here.
 */
private val inlineBitmaps = object : LruCache<String, Bitmap>(16 shl 20) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

/** [file] decoded to at most [maxPx], from [inlineBitmaps] when there. */
internal fun inlineBitmap(file: File, maxPx: Int): Bitmap? {
    val key = "${file.path}@$maxPx"
    inlineBitmaps.get(key)?.let { return it }
    return decodeBitmap(file, maxPx)?.also { inlineBitmaps.put(key, it) }
}

/** Runs [block], turning a failure (not a cancellation) into null: a bubble that can't fetch its media keeps its placeholder. */
internal suspend fun <T> orNull(block: suspend () -> T): T? = try {
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
internal fun PinWhileShown(media: ChatMediaSource?, vararg ids: String?) {
    val keys = ids.filterNotNull()
    DisposableEffect(media, keys) {
        if (media != null) keys.forEach(media::pin)
        onDispose { if (media != null) keys.forEach(media::unpin) }
    }
}

/** The size a picture of [w]×[h] shows at inline: scaled down to fit [MEDIA_MAX] square (the sender's numbers only shape the box). */
private fun inlineSize(w: Int?, h: Int?): Pair<Dp, Dp> {
    if (w == null || h == null || w <= 0 || h <= 0) return 250.dp to 150.dp // the mockup's photo
    val max = MEDIA_MAX.value
    val scale = minOf(1f, max / w, max / h)
    return (w * scale).coerceIn(64f, max).dp to (h * scale).coerceIn(48f, max).dp
}

@Composable
internal fun inlineMaxPx(): Int = with(LocalDensity.current) { MEDIA_MAX.toPx().roundToInt() }

/**
 * A message's attachments: photos and GIFs as pictures, videos as inline players through [videos] (file bubbles without
 * it), files and folders as file bubbles, in sent order. A tap opens a picture, plays a video or downloads a file; a long
 * press opens the message's sheet.
 */
@Composable
internal fun MessageAttachments(
    m: ChatMessage, mine: Boolean, media: ChatMediaSource?, videos: VideoPlayerState?,
    onOpen: (Attachment) -> Unit, onDownload: (List<Attachment>) -> Unit, onLongPress: (() -> Unit)?,
) {
    val groups = remember(m.attachments) { groupAttachments(m.attachments) }
    for (group in groups) {
        when (group) {
            is AttachmentGroup.Folder -> FolderBubble(group.name, group.count, group.totalBytes, mine, { onDownload(group.items) }, onLongPress)
            is AttachmentGroup.Single -> {
                val att = group.item
                when (att.kind) {
                    AttachmentKind.IMAGE -> ImageBubble(att, media, { onOpen(att) }, onLongPress)
                    AttachmentKind.GIF -> GifBubble(att, media, { onOpen(att) }, onLongPress)
                    AttachmentKind.VIDEO ->
                        if (videos != null) VideoBubble(m, att, media, videos, onLongPress)
                        else FileBubble(att.kind, att.name, att.sizeBytes, mine, { onDownload(listOf(att)) }, onLongPress)
                    AttachmentKind.FILE -> FileBubble(att.kind, att.name, att.sizeBytes, mine, { onDownload(listOf(att)) }, onLongPress)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.tapOrHold(label: String, onTap: (() -> Unit)?, onLongPress: (() -> Unit)?): Modifier =
    if (onTap == null && onLongPress == null) this
    else semantics { contentDescription = label }.combinedClickable(
        role = Role.Button, onClick = onTap ?: {}, onLongClick = onLongPress, onLongClickLabel = if (onLongPress != null) "Message actions" else null,
    )

/** A photo: its thumbnail first, then the full picture (at bubble size) once fetched. */
@Composable
private fun ImageBubble(att: Attachment, media: ChatMediaSource?, onOpen: () -> Unit, onLongPress: (() -> Unit)?) {
    PinWhileShown(media, att.fileId, att.thumbFileId)
    val maxPx = inlineMaxPx()
    var thumb by remember(att.fileId) { mutableStateOf<Bitmap?>(null) }
    var full by remember(att.fileId) { mutableStateOf<Bitmap?>(null) }
    val connects by (media?.connects ?: NO_CONNECTS).collectAsState()
    LaunchedEffect(att.fileId, media, connects) { // what failed offline loads again once the relay is back
        if (media == null) return@LaunchedEffect
        if (thumb == null) thumb = orNull { media.thumbnail(att)?.let { withContext(Dispatchers.IO) { inlineBitmap(it, maxPx) } } }
        if (full == null && att.sizeBytes <= INLINE_FULL_MAX_BYTES) {
            full = orNull { media.file(att).let { withContext(Dispatchers.IO) { inlineBitmap(it, maxPx) } } }
        }
    }
    val shown = full ?: thumb
    val (w, h) = inlineSize(att.width ?: shown?.width, att.height ?: shown?.height)
    Box(
        Modifier.size(w, h).clip(mediaShape).background(PLACEHOLDER, mediaShape).border(1.dp, Forge.Border, mediaShape)
            .tapOrHold("Photo ${att.name}", onOpen, onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(Messages.mediaLabel(att.kind), color = Forge.Muted, fontSize = 12.sp)
    }
}

/** A GIF: its first frame (the thumbnail) at once, then animating once fetched; the `GIF` badge in its corner. */
@Composable
private fun GifBubble(att: Attachment, media: ChatMediaSource?, onOpen: () -> Unit, onLongPress: (() -> Unit)?) {
    PinWhileShown(media, att.fileId, att.thumbFileId)
    val maxPx = inlineMaxPx()
    var still by remember(att.fileId) { mutableStateOf<Bitmap?>(null) }
    var gif by remember(att.fileId) { mutableStateOf<AnimatedImageDrawable?>(null) }
    val connects by (media?.connects ?: NO_CONNECTS).collectAsState()
    LaunchedEffect(att.fileId, media, connects) { // what failed offline loads again once the relay is back
        if (media == null) return@LaunchedEffect
        if (still == null) still = orNull { media.thumbnail(att)?.let { withContext(Dispatchers.IO) { inlineBitmap(it, maxPx) } } }
        if (gif == null && att.sizeBytes <= INLINE_FULL_MAX_BYTES) {
            gif = orNull { media.file(att).let { withContext(Dispatchers.IO) { decodeAnimated(it, maxPx, INLINE_FULL_MAX_BYTES) } } }
        }
    }
    val (w, h) = inlineSize(att.width ?: still?.width, att.height ?: still?.height)
    Box(
        Modifier.size(w, h).clip(mediaShape).background(TAG_FILL, mediaShape).border(1.dp, Forge.Border, mediaShape)
            .tapOrHold("GIF ${att.name}", onOpen, onLongPress),
    ) {
        val frames = gif
        if (frames != null) AnimatedGif(frames, Modifier.fillMaxSize(), ImageView.ScaleType.CENTER_CROP)
        else still?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        GifBadge(Modifier.align(Alignment.BottomStart).padding(6.dp))
    }
}

/** [drawable] playing in an ImageView, stopped when it leaves the screen. */
@Composable
private fun AnimatedGif(drawable: AnimatedImageDrawable, modifier: Modifier, scale: ImageView.ScaleType) {
    DisposableEffect(drawable) {
        drawable.start()
        onDispose { drawable.stop() }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = scale } },
        update = { it.setImageDrawable(drawable) },
        modifier = modifier,
    )
}

@Composable
private fun GifBadge(modifier: Modifier = Modifier) = Text(
    "GIF", color = Forge.OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
    modifier = modifier.background(Forge.Text, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
)

/** The file type tag of a file bubble: its extension ("PDF"), else "FILE"; "VIDEO" for a video. */
internal fun fileTag(kind: AttachmentKind, name: String): String =
    if (kind == AttachmentKind.VIDEO) "VIDEO"
    else name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 4 && it != name }?.uppercase() ?: "FILE"

/** A file (or a video still uploading, or with no player): its type tag, its name in bold and `File · 220 KB` / `Video · 18 MB` (ruling CM-R4). A tap downloads it. */
@Composable
private fun FileBubble(kind: AttachmentKind, name: String, bytes: Long, mine: Boolean, onDownload: (() -> Unit)?, onLongPress: (() -> Unit)?) =
    FileLikeBubble(fileTag(kind, name), name, Messages.fileBubbleDetail(kind, bytes), mine, onDownload, onLongPress)

/** A sent folder as one bubble: its name and `Folder · {n} files · {size}` (ruling CM-P3). A tap downloads all of it. */
@Composable
private fun FolderBubble(name: String, count: Int, bytes: Long, mine: Boolean, onDownload: (() -> Unit)?, onLongPress: (() -> Unit)?) =
    FileLikeBubble(null, name, Messages.folderDetail(count, bytes), mine, onDownload, onLongPress)

private val FolderIcon = strokeIcon("folder", "M3 6h6l2 2h10v11H3z")

@Composable
private fun FileLikeBubble(tag: String?, title: String, detail: String, mine: Boolean, onDownload: (() -> Unit)?, onLongPress: (() -> Unit)?) {
    val base = if (mine) Modifier.background(MineFill, mediaShape).border(1.dp, MineBorder, mediaShape) else Modifier.background(Forge.Surface, mediaShape)
    Row(
        Modifier.widthIn(max = MEDIA_MAX + 30.dp).clip(mediaShape).then(base).tapOrHold("${Messages.DOWNLOAD} $title", onDownload, onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).background(TAG_FILL, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            if (tag != null) Text(tag, color = Forge.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            else androidx.compose.material3.Icon(FolderIcon, contentDescription = null, tint = Forge.Muted, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = Forge.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- sends not posted yet ----

/** "400 KB of 1 MB · 40%". */
internal fun sendProgress(done: Long, total: Long): String {
    val fraction = if (total > 0) done.coerceIn(0, total).toFloat() / total else 1f
    return "${Messages.size(done)} of ${Messages.size(total)} · ${(fraction * 100).toInt()}%"
}

/** The button under a pending send: *Dismiss* once it failed for good, else *Cancel*. */
internal fun pendingAction(send: PendingSend): String = if (send.failure != null) Messages.DISMISS else Messages.CANCEL

/** A send still uploading: its files and text greyed, with a progress bar and *Cancel*. One that failed for good shows why, with *Dismiss*. */
@Composable
internal fun PendingSendBubbles(send: PendingSend, onDismiss: () -> Unit) {
    val failed = send.failure != null
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier.alpha(0.5f).semantics { contentDescription = if (failed) "Not sent" else "Sending" },
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (group in remember(send.files) { groupPendingFiles(send.files) }) {
                when (group) {
                    is AttachmentGroup.Folder -> FolderBubble(group.name, group.count, group.totalBytes, mine = true, onDownload = null, onLongPress = null)
                    is AttachmentGroup.Single -> PendingFileBubble(group.item)
                }
            }
            send.text?.takeIf { it.isNotBlank() }?.let { text ->
                val shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
                Text(
                    text, fontSize = 15.sp, lineHeight = 21.sp,
                    modifier = Modifier.widthIn(max = 300.dp).background(MineFill, shape).border(1.dp, MineBorder, shape).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(send.failure!!, Modifier.weight(1f, fill = false), color = FailureInk, fontSize = 12.sp)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(pendingAction(send)) }
            }
        } else {
            val fraction = if (send.totalBytes > 0) send.sentBytes.coerceIn(0, send.totalBytes).toFloat() / send.totalBytes else 0f
            Box(
                Modifier.width(160.dp).height(4.dp).background(Forge.Border, RoundedCornerShape(2.dp))
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
            ) { Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Forge.Accent, RoundedCornerShape(2.dp))) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(sendProgress(send.sentBytes, send.totalBytes), color = Forge.Muted, fontSize = 11.sp)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(pendingAction(send)) }
            }
        }
    }
}

/** A queued photo or GIF from its local thumbnail (or the copy itself); anything else as a file bubble. */
@Composable
private fun PendingFileBubble(f: PendingFile) {
    if (f.kind == AttachmentKind.IMAGE || f.kind == AttachmentKind.GIF) {
        val maxPx = inlineMaxPx()
        var bitmap by remember(f.path) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(f.path) {
            bitmap = orNull { withContext(Dispatchers.IO) { inlineBitmap(File(f.thumbPath?.takeIf { File(it).exists() } ?: f.path), maxPx) } }
        }
        val (w, h) = inlineSize(bitmap?.width, bitmap?.height)
        Box(Modifier.size(w, h).clip(mediaShape).background(PLACEHOLDER, mediaShape).border(1.dp, Forge.Border, mediaShape)) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (f.kind == AttachmentKind.GIF) GifBadge(Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
    } else {
        FileBubble(f.kind, f.name, f.sizeBytes, mine = true, onDownload = null, onLongPress = null)
    }
}

// ---- the viewer ----

/** A photo or GIF full screen, with pinch-zoom (double tap resets), *Download*, *Share…* and *Close*. */
@Composable
internal fun ImageViewer(att: Attachment, media: ChatMediaSource?, onDownload: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    PinWhileShown(media, att.fileId, att.thumbFileId)
    val metrics = LocalResources.current.displayMetrics
    val screenPx = maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(320, VIEWER_MAX_PX)
    val thumbPx = inlineMaxPx()
    var still by remember(att.fileId) { mutableStateOf<Bitmap?>(null) }
    var gif by remember(att.fileId) { mutableStateOf<AnimatedImageDrawable?>(null) }
    var failed by remember(att.fileId) { mutableStateOf(false) }
    LaunchedEffect(att.fileId, media) {
        if (media == null) { failed = true; return@LaunchedEffect }
        still = orNull { media.thumbnail(att)?.let { withContext(Dispatchers.IO) { inlineBitmap(it, thumbPx) } } }
        val file = orNull { media.file(att) }
        if (file == null) { failed = true; return@LaunchedEffect }
        // Decoded at screen size and never cached: one big picture, dropped when the viewer closes. A GIF over the caps keeps its thumbnail.
        val picture = orNull {
            withContext(Dispatchers.IO) {
                viewerPicture(
                    att.kind, file.length(), VIEWER_GIF_MAX_BYTES,
                    animate = { decodeAnimated(file, screenPx, VIEWER_GIF_MAX_BYTES) },
                    still = { maxPixels -> decodeBitmap(file, screenPx, maxPixels) },
                )
            }
        }
        gif = picture?.animated
        still = picture?.still ?: still
    }
    var scale by remember(att.fileId) { mutableFloatStateOf(1f) }
    var offset by remember(att.fileId) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().semantics { contentDescription = "Image viewer" }) {
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(att.fileId) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(att.fileId) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) },
                contentAlignment = Alignment.Center,
            ) {
                val zoomed = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                val frames = gif
                val picture = still
                when {
                    frames != null -> AnimatedGif(frames, zoomed, ImageView.ScaleType.FIT_CENTER)
                    picture != null -> Image(picture.asImageBitmap(), att.name, zoomed, contentScale = ContentScale.Fit)
                    else -> Text(if (failed) "Couldn't load ${att.name}" else "Loading…", color = Forge.Muted, fontSize = 13.sp)
                }
            }
            Text(
                Messages.attachmentDetail(att.name, att.sizeBytes), color = Forge.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) { Text(Messages.DOWNLOAD) }
                TextButton(onClick = onShare, modifier = Modifier.heightIn(min = 48.dp)) { Text(Messages.SHARE) }
                Box(Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
        }
    }
}
