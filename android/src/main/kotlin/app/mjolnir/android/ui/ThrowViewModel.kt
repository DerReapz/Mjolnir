package app.mjolnir.android.ui

import app.mjolnir.client.RelayErrorKind
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.android.data.TrayItem
import app.mjolnir.android.data.toUploads
import app.mjolnir.android.share.Imported
import app.mjolnir.android.throwing.Sensitivity
import app.mjolnir.protocol.Tab
import app.mjolnir.client.DestinationGroup
import app.mjolnir.client.DestinationKey
import app.mjolnir.client.destinationKey
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Nicknames
import app.mjolnir.client.groupDestinations
import app.mjolnir.client.isPaired
import app.mjolnir.client.Messages
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.client.Outbox
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayException
import app.mjolnir.client.sendInBatches
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
    /** Paired devices with their real names; show them through [nameOf] (the nickname when one is set). */
    val targets: List<Peer> = emptyList(),
    /** Private nicknames by device id. */
    val nicknames: Map<String, String> = emptyMap(),
    /** The device thrown to last (remembered in settings); it leads the selector. Not the current selection, so tapping does not reshuffle. */
    val lastUsedId: String? = null,
    /** The chosen card: a device in the context of one space (null: a paired device). */
    val targetKey: DestinationKey? = null,
    val phase: ThrowPhase = ThrowPhase.Idle,
    val notice: String? = null,
    val outgoing: List<OutgoingFiles> = emptyList(),
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
) {
    /** [targets] grouped for the selector; a single group has no title. */
    val groups: List<DestinationGroup> get() = groupDestinations(targets, lastUsedId, ::nameOf)

    /** The chosen device; its cards under every heading are highlighted (see [isSelected]). */
    val targetId: String? get() = targetKey?.deviceId

    /** The chosen card's peer; its `space` is the space a throw is made in. */
    val target: Peer? get() = targets.find { it.destinationKey == targetKey }

    /** True for every card of the chosen device, whichever heading it sits under. */
    fun isSelected(peer: Peer): Boolean = peer.deviceId == targetKey?.deviceId

    /** The name to show for [peer]: its nickname, else its real name. */
    fun nameOf(peer: Peer): String = nicknames[peer.deviceId] ?: peer.name

    val canThrow: Boolean get() = targetKey != null && selected.isNotEmpty() && phase == ThrowPhase.Idle
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
    private val nicknames: Nicknames,
    private val scheduleOutgoing: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Called when the relay rejects this device's token, so the registration is reset (must not throw). */
    private val onUnauthorized: suspend () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(ThrowUiState())

    val state: StateFlow<ThrowUiState> = _state.asStateFlow()

    /** Serializes store writes so they land in the order they were made. */
    private val writes = io.limitedParallelism(1)

    /** Until the stored tray has been read, writing it would lose its items; changes made before then are merged in. */
    private var trayLoaded = false
    private var trayChangedBeforeLoad = false

    /** Every tray id this view model has loaded or written; other ids in the stored tray came from the background. */
    private val everWritten = HashSet<String>()

    init {
        viewModelScope.launch {
            nicknames.changes.collect { shown -> _state.update { s -> s.copy(nicknames = shown) } }
        }
        viewModelScope.launch {
            val stored = try {
                withContext(io) { tray.read() }
            } catch (e: IOException) {
                return@launch // keep the file untouched; this session's changes stay in memory only
            }
            val storedIds = stored.mapTo(HashSet()) { it.id }
            everWritten += storedIds
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

    /**
     * Imports a share in [viewModelScope], so it survives the activity being recreated mid-copy. [importFiles] does
     * the blocking copying (run on [io]) and must not capture an Activity.
     */
    fun importShared(tabs: List<Tab>, importFiles: (() -> Imported)?) {
        viewModelScope.launch {
            val files = if (importFiles == null) Imported(emptyList(), emptyList())
            else try {
                withContext(io) { importFiles() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // e.g. a SecurityException when the sharing app's grant was revoked
                Imported(emptyList(), listOf(Messages.unreadable("what you shared")))
            }
            val items = tabs.map { TrayItem.Link(it) } + files.items
            addShared(items, files.notices)
        }
    }

    /** Re-reads the throw sensitivity from settings; call when the throw screen is entered. */
    fun refreshSensitivity() {
        viewModelScope.launch {
            val value = try { withContext(io) { config.read().sensitivity } } catch (e: IOException) { return@launch }
            _state.update { it.copy(sensitivity = value) }
        }
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

    /** Selects [deviceId]'s paired card, else its first card. */
    fun selectTarget(deviceId: String) {
        val cards = _state.value.targets.filter { it.deviceId == deviceId }.map { it.destinationKey }
        (cards.firstOrNull { it.isPaired } ?: cards.firstOrNull())?.let(::selectTarget)
    }

    /** Selects the card [key] (a device under one heading); unknown cards change nothing. */
    fun selectTarget(key: DestinationKey) {
        if (_state.value.targets.none { it.destinationKey == key }) return
        _state.update { it.copy(targetKey = key) }
        viewModelScope.launch(writes) {
            try {
                config.update { it.copy(targetPcId = key.deviceId, targetSpaceId = key.spaceId) } // persisted key keeps its old name
            } catch (e: IOException) {
                // Only the default for next time is lost.
            }
        }
    }

    /**
     * Loads every destination (paired devices and space members) and picks the target: the current card, the
     * remembered one (its space too), the remembered device's paired card, or the first.
     */
    fun refreshTargets() {
        viewModelScope.launch {
            val peers = try {
                api.peers()
            } catch (e: RelayException) {
                return@launch
            }
            val (rememberedId, rememberedSpace) = try {
                withContext(io) { config.read().let { it.targetPcId to it.targetSpaceId } }
            } catch (e: IOException) {
                null to null
            }
            val keys = peers.map { it.destinationKey }
            val remembered = rememberedId?.let { id ->
                keys.firstOrNull { it.deviceId == id && it.spaceId == rememberedSpace }
                    ?: keys.firstOrNull { it.deviceId == id && it.isPaired }
                    ?: keys.firstOrNull { it.deviceId == id }
            }
            _state.update { s ->
                val target = s.targetKey?.takeIf { it in keys } ?: remembered ?: keys.firstOrNull()
                s.copy(targets = peers, targetKey = target, lastUsedId = rememberedId)
            }
        }
    }

    /**
     * Re-reads the outbox, and takes in tray items the background worker gave back (files of a failed throw). Call
     * when a background throw finishes.
     */
    fun refreshOutgoing() {
        viewModelScope.launch {
            val pending = try {
                withContext(io) { outbox.pending() }
            } catch (e: IOException) {
                return@launch
            }
            _state.update { it.copy(outgoing = pending) }
        }
        if (!trayLoaded) return // the initial load picks them up
        viewModelScope.launch {
            val stored = try {
                withContext(writes) { tray.read() } // after this view model's own pending writes
            } catch (e: IOException) {
                return@launch
            }
            val returned = stored.filter { it.id !in everWritten }
            if (returned.isEmpty()) return@launch
            _state.update { s ->
                val known = s.tray.mapTo(HashSet()) { it.id }
                val fresh = returned.filter { it.id !in known }
                s.copy(tray = s.tray + fresh, selected = s.selected + fresh.map { it.id })
            }
            // Now this view model's own: removing or throwing one must also drop it from the store.
            returned.mapTo(everWritten) { it.id }
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
        val pc = s.target ?: return
        val space = pc.space
        val shown = s.nameOf(pc) // for messages; the queued item keeps the real name (pc.name)
        val chosen = s.tray.filter { it.id in s.selected }
        val links = chosen.filterIsInstance<TrayItem.Link>()
        val fileItems = chosen.filter { it !is TrayItem.Link }
        val parts = mutableListOf<String>()
        val gone = HashSet<String>()

        if (links.isNotEmpty()) {
            val outcome = sendInBatches(api, pc.deviceId, links.map { it.tab }, spaceId = space?.id)
            val sentUrls = outcome.sentTabs.mapTo(HashSet()) { it.url }
            links.filter { it.tab.url in sentUrls }.mapTo(gone) { it.id }
            if (outcome.sentTabs.isNotEmpty()) parts += Messages.thrown(outcome.sentTabs.size, 0, shown, space?.name)
            outcome.error?.let { error ->
                if (error.kind == RelayErrorKind.UNAUTHORIZED) {
                    parts += Messages.RESET
                    try {
                        onUnauthorized()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Registering again failed; the app registers on its next start.
                    }
                } else {
                    parts += Messages.notThrown(Messages.failureReason(error.kind, shown))
                }
            }
        }

        if (fileItems.isNotEmpty()) {
            var queuedFiles = 0
            var enqueueFailed = false
            for (group in groupForOutbox(fileItems)) {
                val uploads = group.flatMap { it.toUploads() }
                val item = OutgoingFiles(UUID.randomUUID().toString(), pc.deviceId, pc.name, uploads,
                    createdAtEpochMs = System.currentTimeMillis(), spaceId = space?.id, spaceName = space?.name)
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
            if (queuedFiles > 0) parts += Messages.queued(queuedFiles, shown)
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
        snapshot.mapTo(everWritten) { it.id }
        val written = everWritten.toSet()
        viewModelScope.launch(writes) {
            try {
                // Items this view model never had were added by the background worker: keep them.
                tray.update { stored -> snapshot + stored.filter { it.id !in written } }
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
