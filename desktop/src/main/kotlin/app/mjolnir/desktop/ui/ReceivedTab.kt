package app.mjolnir.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.desktop.AppController
import app.mjolnir.desktop.Kind
import app.mjolnir.desktop.ReceivedEntry
import app.mjolnir.desktop.TabOpener
import app.mjolnir.protocol.Tab
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GONE = "That file is no longer there."

/** `Today, 14:32`, `Yesterday, 09:05`, `12 Nov, 23:59`, or with the year for an older one. */
fun formatReceivedTime(atEpochMs: Long, nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(atEpochMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    val clock = at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
    return when (at.toLocalDate()) {
        today -> "Today, $clock"
        today.minusDays(1) -> "Yesterday, $clock"
        else -> at.format(DateTimeFormatter.ofPattern(if (at.year == today.year) "d MMM, HH:mm" else "d MMM yyyy, HH:mm", Locale.ENGLISH))
    }
}

/** The Received tab: what this PC has caught, newest first (the controller's history keeps the last 100). */
@Composable
fun ReceivedTab(c: AppController) {
    val entries by c.history.entries.collectAsState()
    val nicknames by c.nicknames.changes.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val now = remember(entries) { System.currentTimeMillis() }

    fun open(e: ReceivedEntry) {
        notice = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (e.kind == Kind.LINK) TabOpener().open(listOf(Tab(e.target))) > 0 else openFile(File(e.target))
            }
            if (!ok) notice = if (e.kind == Kind.LINK) "Couldn't open that link." else GONE
        }
    }
    fun show(e: ReceivedEntry) {
        notice = null
        scope.launch { if (!withContext(Dispatchers.IO) { revealInFolder(File(e.target)) }) notice = GONE }
    }

    ReceivedTabContent(
        entries, nicknameOf = { nicknames[it.fromDeviceId] ?: it.fromName }, whenOf = { formatReceivedTime(it.atEpochMs, now) },
        onOpen = ::open, onShowInFolder = ::show, onOpenFolder = c::openReceivedFiles, notice = notice,
    )
}

/** Stateless Received tab, so it can be tested and rendered headless. */
@Composable
fun ReceivedTabContent(
    entries: List<ReceivedEntry>,
    nicknameOf: (ReceivedEntry) -> String,
    whenOf: (ReceivedEntry) -> String,
    onOpen: (ReceivedEntry) -> Unit,
    onShowInFolder: (ReceivedEntry) -> Unit,
    onOpenFolder: () -> Unit,
    notice: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TabTitle("Received", Modifier.weight(1f))
            ForgeSecondaryButton("Open received files folder", onClick = onOpenFolder)
        }
        notice?.let { Text(it, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.error)) }
        if (entries.isEmpty()) {
            Text(
                "Nothing received yet. Links and files sent to this PC show up here.",
                style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 14.sp, color = Forge.muted),
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).forgeCard(selected = false).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EntryTile(e)
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = TextStyle(fontFamily = ForgeFonts.inter500, fontSize = 14.sp, color = Forge.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${nicknameOf(e)} · ${whenOf(e)}",
                                style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ForgeSecondaryButton("Open", onClick = { onOpen(e) })
                        if (e.kind != Kind.LINK) ForgeSecondaryButton("Show in folder", onClick = { onShowInFolder(e) })
                    }
                }
            }
        }
    }
}

/** The tab heading: Space Grotesk 700, 22 px. */
@Composable
internal fun TabTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = TextStyle(fontFamily = ForgeFonts.grotesk700, fontSize = 22.sp, color = Forge.text))

@Composable
private fun EntryTile(e: ReceivedEntry) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color(0xFF262626)), contentAlignment = Alignment.Center) {
        when (e.kind) {
            Kind.LINK -> Text(siteLetter(e.target.substringAfter("://", e.target)), style = TextStyle(fontFamily = ForgeFonts.grotesk700, fontSize = 18.sp, color = Forge.text))
            Kind.FOLDER -> ForgeIcon(ICON_FOLDER, Forge.muted, 20.dp)
            Kind.FILE -> Text(
                File(e.target).extension.uppercase().ifEmpty { "File" }.take(4),
                style = TextStyle(fontFamily = ForgeFonts.inter600, fontSize = 11.sp, color = Forge.muted), maxLines = 1,
            )
        }
    }
}
