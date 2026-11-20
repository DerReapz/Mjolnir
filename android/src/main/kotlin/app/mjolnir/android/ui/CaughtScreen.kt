package app.mjolnir.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mjolnir.android.data.CaughtItem
import app.mjolnir.android.receive.openDownloads
import app.mjolnir.android.receive.openFile
import app.mjolnir.android.receive.openTabs
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Messages
import app.mjolnir.client.Nicknames
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Caught list, reloaded whenever [changes] emits (after each inbox drain). */
class CaughtViewModel(
    private val caught: JsonFileStore<List<CaughtItem>>,
    changes: Flow<Long>,
    private val io: CoroutineDispatcher,
    nicknames: Nicknames,
) : ViewModel() {
    /** Current nicknames, so a rename shows in the list at once. */
    val nicknames: StateFlow<Map<String, String>> = nicknames.changes

    private val _items = MutableStateFlow<List<CaughtItem>>(emptyList())
    val items: StateFlow<List<CaughtItem>> = _items.asStateFlow()

    init {
        viewModelScope.launch { changes.collect { reload() } }
    }

    private suspend fun reload() {
        try {
            _items.value = withContext(io) { caught.read() }
        } catch (e: IOException) {
            // keep what is shown; the next change reloads
        }
    }

    /** Clears the list only; received files stay in Downloads. */
    fun clear() {
        viewModelScope.launch {
            try {
                withContext(io) { caught.write(emptyList()) }
                _items.value = emptyList()
            } catch (e: IOException) {
            }
        }
    }
}

@Composable
fun CaughtScreen(vm: CaughtViewModel, modifier: Modifier = Modifier) {
    val items by vm.items.collectAsState()
    val nicknames by vm.nicknames.collectAsState()
    val context = LocalContext.current
    val tabs = items.mapNotNull { it.tab }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { openTabs(context, tabs) }, enabled = tabs.isNotEmpty()) { Text("Open all links") }
            TextButton(onClick = vm::clear, enabled = items.isNotEmpty()) { Text("Clear") }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing caught yet", color = Forge.Muted) }
        } else {
            val now = System.currentTimeMillis()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(items) { _, item ->
                    CaughtRow(item, caughtFrom(item, nicknames), now) {
                        when {
                            item.tab != null -> openTabs(context, listOf(item.tab))
                            item.file != null -> openFile(context, item.file)
                            item.folder != null -> openDownloads(context)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CaughtRow(item: CaughtItem, from: String, now: Long, onClick: () -> Unit) {
    val (glyph, title, detail) = when {
        item.tab != null -> Triple("🔗", item.tab.title ?: item.tab.url, if (item.tab.title != null) item.tab.url else null)
        item.file != null -> Triple("📄", item.file.name, Messages.size(item.file.sizeBytes))
        item.folder != null -> Triple("📁", item.folder.name, Messages.items(0, item.folder.fileCount))
        else -> Triple("•", "", null)
    }
    val ago = DateUtils.getRelativeTimeSpanString(item.caughtAtEpochMs, now, DateUtils.MINUTE_IN_MILLIS)
    ForgeCard(onClick = onClick) {
        Text(glyph, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Forge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "from $from · $ago",
                style = MaterialTheme.typography.bodySmall,
                color = Forge.Muted,
            )
        }
    }
}

/** The sender as shown: its nickname now, else the name it had when caught. */
internal fun caughtFrom(item: CaughtItem, nicknames: Map<String, String>): String =
    item.fromDeviceId?.let { nicknames[it] } ?: item.fromName
