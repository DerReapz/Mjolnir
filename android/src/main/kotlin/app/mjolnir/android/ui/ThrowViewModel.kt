package app.mjolnir.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.android.data.TrayItem
import app.mjolnir.android.data.toUploads
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Messages
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.client.Outbox
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayException
import app.mjolnir.client.sendInBatches
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ThrowPhase {
    data object Idle : ThrowPhase
    data object Armed : ThrowPhase
    data object Throwing : ThrowPhase
}

data class ThrowUiState(
    val tray: List<TrayItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val pcs: List<Peer> = emptyList(),
    val targetPcId: String? = null,
    val phase: ThrowPhase = ThrowPhase.Idle,
    val notice: String? = null,
    val outgoing: List<OutgoingFiles> = emptyList(),
) {
    val canThrow: Boolean get() = targetPcId != null && selected.isNotEmpty() && phase == ThrowPhase.Idle
}

/**
 * The tray of shared links, files and folders and the hold-and-flick throw. State changes happen on the main thread;
 * store and file I/O run on [io]. Links are sent right away; files are handed to the [outbox] and [scheduleOutgoing].
 */
class ThrowViewModel(
    private val api: RelayApi,
    private val tray: JsonFileStore<List<TrayItem>>,
    private val config: JsonFileStore<PhoneConfig>,
    private val outbox: Outbox,
    private val scheduleOutgoing: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(ThrowUiState())
    val state: StateFlow<ThrowUiState> = _state.asStateFlow()

    /** Serializes store writes so they land in the order they were made. */
    private val writes = io.limitedParallelism(1)

    /** Until the stored tray has been read, writing it would lose its items; changes made before then are merged in. */
    private var trayLoaded = false
    private var trayChangedBeforeLoad = false

    init {
        viewModelScope.launch {
            val stored = try {
                withContext(io) { tray.read() }
            } catch (e: IOException) {
                return@launch // keep the file untouched; this session's changes stay in memory only
            }
            val storedIds = stored.mapTo(HashSet()) { it.id }
            _state.update { s ->
                s.copy(tray = stored + s.tray.filter { it.id !in storedIds }, selected = s.selected + storedIds)
            }
            trayLoaded = true
            if (trayChangedBeforeLoad) persistTray()
        }
        refreshOutgoing()
    }

    fun addShared(items: List<TrayItem>, notices: List<String> = emptyList()) {
        val known = _state.value.tray.mapTo(HashSet()) { it.id }
        val fresh = items.distinctBy { it.id }.filter { it.id !in known }
        _state.update { s ->
            s.copy(
                tray = s.tray + fresh,
                selected = s.selected + items.map { it.id },
                notice = when {
                    notices.isNotEmpty() -> notices.joinToString("\n")
                    items.isEmpty() -> Messages.NO_LINKS
                    else -> s.notice
                },
            )
        }
        if (fresh.isNotEmpty()) persistTray()
    }

    fun toggle(id: String) {
        _state.update { s ->
            when {
                s.tray.none { it.id == id } -> s
                id in s.selected -> s.copy(selected = s.selected - id)
                else -> s.copy(selected = s.selected + id)
            }
        }
    }

    /** Removes an item; a LocalFile's outbox copy is deleted. Ignored mid-throw, while the item may be being queued. */
    fun remove(id: String) {
        val s = _state.value
        if (s.phase == ThrowPhase.Throwing) return
        val item = s.tray.find { it.id == id } ?: return
        _state.update { it.copy(tray = it.tray.filter { t -> t.id != id }, selected = it.selected - id) }
        persistTray()
        if (item is TrayItem.LocalFile) viewModelScope.launch(io) { File(item.path).delete() }
    }

    fun selectTarget(pcId: String) {
        if (_state.value.pcs.none { it.deviceId == pcId }) return
        _state.update { it.copy(targetPcId = pcId) }
        viewModelScope.launch(writes) {
            try {
                config.update { it.copy(targetPcId = pcId) }
            } catch (e: IOException) {
                // Only the default for next time is lost.
            }
        }
    }

    /** Loads the paired PCs (phones are not throw targets) and picks the target: current, remembered, or the first. */
    fun refreshPcs() {
        viewModelScope.launch {
            val peers = try {
                api.peers()
            } catch (e: RelayException) {
                return@launch
            }
            val remembered = try {
                withContext(io) { config.read().targetPcId }
            } catch (e: IOException) {
                null
            }
            val pcs = peers.filter { it.kind == DeviceKind.PC }
            val ids = pcs.mapTo(HashSet()) { it.deviceId }
            _state.update { s ->
                val target = s.targetPcId?.takeIf { it in ids } ?: remembered?.takeIf { it in ids } ?: pcs.firstOrNull()?.deviceId
                s.copy(pcs = pcs, targetPcId = target)
            }
        }
    }

    fun refreshOutgoing() {
        viewModelScope.launch {
            val pending = try {
                withContext(io) { outbox.pending() }
            } catch (e: IOException) {
                return@launch
            }
            _state.update { it.copy(outgoing = pending) }
        }
    }

    fun onHoldStart() {
        _state.update { if (it.canThrow) it.copy(phase = ThrowPhase.Armed) else it }
    }

    fun onHoldEnd() {
        _state.update { if (it.phase == ThrowPhase.Armed) it.copy(phase = ThrowPhase.Idle) else it }
    }

    fun onFlick() {
        if (_state.value.phase != ThrowPhase.Armed) return
        _state.update { it.copy(phase = ThrowPhase.Throwing) }
        viewModelScope.launch {
            try {
                throwSelected()
            } finally {
                _state.update { it.copy(phase = ThrowPhase.Idle) }
            }
        }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    private suspend fun throwSelected() {
        val s = _state.value
        val pc = s.pcs.find { it.deviceId == s.targetPcId } ?: return
        val chosen = s.tray.filter { it.id in s.selected }
        val links = chosen.filterIsInstance<TrayItem.Link>()
        val fileItems = chosen.filter { it !is TrayItem.Link }
        val parts = mutableListOf<String>()
        val gone = HashSet<String>()

        if (links.isNotEmpty()) {
            val outcome = sendInBatches(api, pc.deviceId, links.map { it.tab })
            val sentUrls = outcome.sentTabs.mapTo(HashSet()) { it.url }
            links.filter { it.tab.url in sentUrls }.mapTo(gone) { it.id }
            if (outcome.sentTabs.isNotEmpty()) parts += Messages.thrown(outcome.sentTabs.size, 0, pc.name)
            outcome.error?.let { parts += Messages.notThrown(Messages.failureReason(it.kind, pc.name)) }
        }

        if (fileItems.isNotEmpty()) {
            var queuedFiles = 0
            var enqueueFailed = false
            for (group in groupForOutbox(fileItems)) {
                val uploads = group.flatMap { it.toUploads() }
                val item = OutgoingFiles(UUID.randomUUID().toString(), pc.deviceId, pc.name, uploads,
                    createdAtEpochMs = System.currentTimeMillis())
                try {
                    withContext(io) { outbox.enqueue(item) }
                } catch (e: IOException) {
                    enqueueFailed = true
                    continue // the group stays in the tray
                }
                scheduleOutgoing(item.id)
                group.mapTo(gone) { it.id }
                queuedFiles += uploads.size
            }
            if (queuedFiles > 0) parts += Messages.queued(queuedFiles, pc.name)
            if (enqueueFailed) parts += Messages.notThrown("this phone couldn't queue the files")
        }

        _state.update {
            it.copy(tray = it.tray.filter { t -> t.id !in gone }, selected = it.selected - gone, notice = joinNotice(parts) ?: it.notice) // a failed enqueue always adds a part, replacing any stale notice
        }
        if (gone.isNotEmpty()) persistTray()
        refreshOutgoing()
    }

    /** Splits items in order into groups of at most MAX_FILES_PER_SEND files, never splitting one item. */
    private fun groupForOutbox(items: List<TrayItem>): List<List<TrayItem>> {
        val groups = mutableListOf<MutableList<TrayItem>>()
        var count = 0
        for (item in items) {
            val n = item.toUploads().size
            if (n == 0) continue
            if (groups.isEmpty() || count + n > Limits.MAX_FILES_PER_SEND) {
                groups += mutableListOf<TrayItem>()
                count = 0
            }
            groups.last() += item
            count += n
        }
        return groups
    }

    private fun persistTray() {
        if (!trayLoaded) {
            trayChangedBeforeLoad = true
            return
        }
        val snapshot = _state.value.tray
        viewModelScope.launch(writes) {
            try {
                tray.write(snapshot)
            } catch (e: IOException) {
                // The tray stays correct in memory; the next change writes it again.
            }
        }
    }
}

/** Joins notice parts with ". ", or a single space after a part that already ends in a full stop. */
internal fun joinNotice(parts: List<String>): String? {
    if (parts.isEmpty()) return null
    return buildString {
        for ((i, part) in parts.withIndex()) {
            if (i > 0) append(if (parts[i - 1].endsWith('.')) " " else ". ")
            append(part)
        }
    }
}
