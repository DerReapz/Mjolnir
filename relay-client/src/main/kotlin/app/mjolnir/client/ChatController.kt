package app.mjolnir.client

import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.MessageReceipt
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Receipt
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.RelayEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A message by someone else in a chat that isn't open: what the apps turn into a notification (see [Messages.chatNotification]). */
data class IncomingMessage(
    val chatId: String,
    val chatName: String,
    val authorId: String,
    val authorName: String,
    /** The message's text, or for one with only attachments the label of the first ([Messages.messagePreview]: "Photo"). */
    val text: String,
    /** The message mentions this device: it gets the louder [Messages.mentionNotification], even if the chat was read elsewhere. */
    val mentionedMe: Boolean = false,
)

/** A file of a [PendingSend]: what its greyed bubble shows. [path] is the local file, [thumbPath] its thumbnail, if any. */
data class PendingFile(
    val name: String,
    val kind: AttachmentKind,
    val sizeBytes: Long,
    val relativePath: String?,
    val path: String,
    val thumbPath: String?,
)

/**
 * A send with attachments not posted yet, shown as greyed bubbles at the end of its chat. [files] and [text] are what is
 * still to be posted; [sentBytes] of [totalBytes] is the progress of the whole send. [failure] is set once it failed for good
 * (e.g. "Not sent: the relay is out of space."); [ChatController.dismissSend] removes it then.
 */
data class PendingSend(
    val id: String,
    val chatId: String,
    val text: String?,
    val files: List<PendingFile>,
    val sentBytes: Long,
    val totalBytes: Long,
    val failure: String? = null,
)

/**
 * The chat side of the client, shared by desktop and Android: the chat list across all of the device's spaces, the
 * invites it can act on, and each chat's messages, cached on disk in [store] and kept current by [sync].
 *
 * User actions ([send], [createChat], ...) throw the [RelayException] of a refused call; the relay's code is its message.
 * Background work ([refresh], [sync], [onEvent]) swallows network failures and keeps what it has.
 *
 * Sends with files go through [outbox] (see [pendingSends]); [download] goes through [media]. Without them those calls fail.
 */
class ChatController(
    private val api: () -> RelayApi?,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val outbox: ChatOutbox? = null,
    private val media: ChatMediaCache? = null,
    private val me: () -> String?,
) {
    private class Live(
        val messages: MutableStateFlow<List<ChatMessage>>,
        val members: MutableStateFlow<List<Peer>>,
        val receipts: MutableStateFlow<List<Receipt>>,
    )

    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    /** The chats this device may see, across all its spaces (the space admin sees every chat of its space). */
    val chats: StateFlow<List<ChatSummary>> = _chats.asStateFlow()

    private val _invites = MutableStateFlow<List<ChatInvite>>(emptyList())
    /** Invites this device can act on: approvals it owes as a chat's owner and invitations to join. */
    val invites: StateFlow<List<ChatInvite>> = _invites.asStateFlow()

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    /**
     * Per chat, the messages by others (no system entries, no deleted ones) above the chat's read marker. Chats without
     * any are absent. Counted from the messages, never as `rev - readRev`: every chat change bumps the rev.
     */
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    private val _unreadMentions = MutableStateFlow<Set<String>>(emptySet())
    /** The chats with an unread message (above the read marker, by someone else, not deleted) that mentions this device. */
    val unreadMentions: StateFlow<Set<String>> = _unreadMentions.asStateFlow()

    private val presenceTracker = PresenceTracker()
    /** Who of the device's spaces is online, by device id; the apps read presence only through this. */
    val presence: StateFlow<Map<String, Presence>> get() = presenceTracker.presence

    /** Seeds [presence] from [spaces] (their members carry online and last seen); the apps call this wherever they load spaces. */
    fun seedPresence(spaces: List<Space>) = presenceTracker.seed(spaces)

    /** The chat on screen, if any: its incoming messages don't go to [newMessages]. */
    val openChat = MutableStateFlow<String?>(null)

    private val _newMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    /**
     * Messages by others in chats that aren't open, for notifications. Start collecting before calling [refresh] or
     * [sync]: with no collector an emission is dropped. A slow collector holds the sync back (nothing is lost in bursts).
     */
    val newMessages: SharedFlow<IncomingMessage> = _newMessages.asSharedFlow()

    private val _pendingSends = MutableStateFlow(outbox?.pending().orEmpty().map(::pendingOf))
    /** Sends with attachments still uploading or waiting to post (kept across restarts), oldest first; failed ones until dismissed. */
    val pendingSends: StateFlow<List<PendingSend>> = _pendingSends.asStateFlow()
    private val sendLock = Mutex()
    @Volatile private var rateLimitRetry: Job? = null

    private val _connects = MutableStateFlow(0)
    /** Counts the relay connections ([RelayEvent.Connected]): media that failed to load offline tries again when it changes. */
    val connects: StateFlow<Int> = _connects.asStateFlow()

    private val _sendNotices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** What a send could not take along ("clip.mov is too big for the relay"), for the app's notice line. */
    val sendNotices: SharedFlow<String> = _sendNotices.asSharedFlow()

    /** Bumped by [clear]: background work that was suspended across a clear drops its results instead of writing them back. */
    @Volatile private var generation = 0

    private val live = ConcurrentHashMap<String, Live>()
    private val syncLocks = ConcurrentHashMap<String, Mutex>()

    private fun live(chatId: String): Live = live.getOrPut(chatId) {
        val cached = store.read(chatId)
        Live(MutableStateFlow(ordered(cached)), MutableStateFlow(cached.members), MutableStateFlow(cached.receipts))
    }

    /** Messages oldest first by send time: an edit gets a new rev but keeps its place. */
    private fun ordered(c: CachedChat) = c.messages.values.sortedWith(compareBy({ it.sentAtEpochMs }, { it.id }))

    /** The messages of [chatId] from the cache, kept current by [sync]; system entries (see [ChatMessage.event]) included. */
    fun messages(chatId: String): StateFlow<List<ChatMessage>> = live(chatId).messages.asStateFlow()

    fun members(chatId: String): StateFlow<List<Peer>> = live(chatId).members.asStateFlow()

    /** Who has seen and read how much of [chatId], one entry per member (never a space admin who is not in it). */
    fun receipts(chatId: String): StateFlow<List<Receipt>> = live(chatId).receipts.asStateFlow()

    /** Members of [chatId] that [query] could mean when typed after an `@`: everyone but this device, matched on [shownName]. */
    fun mentionCandidates(chatId: String, query: String, shownName: (Peer) -> String = { it.name }): List<Peer> =
        mentionCandidates(members(chatId).value, me(), query, shownName)

    private fun requireApi(): RelayApi = api() ?: throw RelayException(RelayErrorKind.NETWORK, "not connected")

    // ---- Reading ----

    /** Re-reads the chat list of every space (re-seeding [presence] from them), forgets chats that are gone, and syncs those that moved on. */
    suspend fun refresh() = refreshAll(null)

    /** [trigger] is the chat and rev of the `ChatChanged` that caused this refresh: its messages from that rev on are news. */
    private suspend fun refreshAll(trigger: Pair<String, Long>?) {
        val a = api() ?: return
        val gen = generation
        val summaries = try {
            val spaces = a.spaces()
            if (gen == generation) presenceTracker.seed(spaces) // the full list: catches up on presence missed while offline
            spaces.flatMap { space ->
                try { a.chats(space.id) } catch (e: RelayException) { if (e.kind == RelayErrorKind.NOT_FOUND) emptyList() else throw e }
            }
        } catch (e: RelayException) {
            return
        }
        if (gen != generation) return
        val ids = summaries.mapTo(HashSet()) { it.id }
        (_chats.value.map { it.id } + store.cachedIds()).filter { it !in ids }.toSet().forEach(::forget)
        for (s in summaries) {
            val c = store.read(s.id)
            if (c.name != s.name || c.readRev < s.readRev || (s.rev == 0L && !c.listedEmpty && c.rev == 0L)) {
                store.update(s.id) { it.copy(name = s.name, readRev = maxOf(it.readRev, s.readRev), listedEmpty = it.listedEmpty || (s.rev == 0L && it.rev == 0L)) }
            }
        }
        // Listed empty before (in this run, or remembered on disk): its first messages are news (a chat listed with history and no cache is unseen history).
        val known = (_chats.value.filter { it.rev == 0L }.map { it.id } + store.cachedIds().filter { store.read(it).listedEmpty }).toHashSet()
        _chats.value = summaries
        listed = true
        recountUnread()
        for (s in summaries) {
            if (gen != generation) return
            val cached = store.read(s.id)
            val have = cached.rev
            if (have >= s.rev) { if (s.member) reportSeen(s.id); continue }
            // With a cache (or a watermark, as a join leaves even when its sync failed), everything above it is news.
            // Without one it is history, except what the triggering event announced.
            val floor = when {
                have > 0 || cached.announcedRev > 0 -> null
                s.id in known -> cached.announcedRev
                trigger != null && trigger.first == s.id -> trigger.second - 1
                else -> Long.MAX_VALUE
            }
            syncChat(s.id, floor)
        }
    }

    suspend fun refreshInvites() {
        val a = api() ?: return
        try { _invites.value = a.chatInvites() } catch (e: RelayException) { /* keep the list */ }
    }

    /** Fetches what [chatId] gained since the cache's rev, page after page; a NOT_FOUND answer forgets the chat. */
    suspend fun sync(chatId: String) = syncChat(chatId, null)

    /**
     * Messages with rev above [floor] are announced to [newMessages]; Long.MAX_VALUE announces none. Without a [floor]:
     * everything above the cache's announced watermark when the chat has a history here, else nothing (it is history).
     */
    private suspend fun syncChat(chatId: String, floor: Long?) {
        val a = api() ?: return
        val gen = generation
        syncLocks.getOrPut(chatId) { Mutex() }.withLock {
            if (gen != generation) return
            val before = store.read(chatId)
            val hasHistory = before.rev > 0 || before.announcedRev > 0 || _chats.value.firstOrNull { it.id == chatId }?.rev == 0L
            val start = floor ?: if (hasHistory) before.announcedRev else Long.MAX_VALUE
            // New to the user: above the start, and not an edit of something already announced.
            val self = me()
            // A mention of this device added by an edit is news too, though the message itself was announced before.
            fun addedMention(m: ChatMessage) = m.rev > start && self != null && self in m.mentions && before.messages[m.id]?.let { self !in it.mentions } == true
            fun fresh(m: ChatMessage) = m.rev > start && (before.messages[m.id]?.let { it.rev > before.announcedRev } ?: true) || addedMention(m)
            var since = before.rev
            var latest = before
            var renamed: String? = null
            var synced = false
            try {
                announce(chatId, before.messages.values.filter(::fresh), before, gen) // fetched earlier, never announced
                while (true) {
                    val page = a.syncChat(chatId, since)
                    if (gen != generation) return
                    val last = page.messages.maxOfOrNull { it.rev }
                    val done = !page.hasMore || last == null || last <= since
                    // A page cut short keeps the rev of its last message, so an interrupted sync resumes where it stopped.
                    latest = store.update(chatId) { c ->
                        c.copy(
                            rev = if (done) page.rev else last!!,
                            ownerId = page.ownerId,
                            members = page.members,
                            receipts = page.receipts,
                            messages = c.messages + page.messages.associateBy { it.id },
                            listedEmpty = c.listedEmpty && page.messages.isEmpty(), // an empty page is not history yet
                        )
                    }
                    (page.messages.filter { it.event is ChatEvent.Renamed }.maxByOrNull { it.rev }?.event as? ChatEvent.Renamed)?.let { renamed = it.name }
                    show(chatId, latest)
                    announce(chatId, page.messages.filter(::fresh).map { latest.messages.getValue(it.id) }, latest, gen)
                    if (gen != generation) return
                    // Only now, so a failure before this point leaves the page's messages to be announced on the retry.
                    val upTo = if (done) page.rev else last!!
                    latest = store.update(chatId) { it.copy(announcedRev = maxOf(it.announcedRev, upTo)) }
                    if (done) break
                    since = last!!
                }
                synced = true
            } catch (e: RelayException) {
                if (gen != generation) return
                if (e.kind == RelayErrorKind.NOT_FOUND) { forget(chatId); return }
            }
            if (gen != generation) return
            refreshSummary(chatId, latest, renamed)
            if (synced) afterSync(chatId)
        }
    }

    /** Replaces the receipts of the members in [changed], in the cache and on screen. */
    private fun applyReceipts(chatId: String, changed: List<Receipt>) {
        if (_chats.value.none { it.id == chatId }) return
        val cached = store.update(chatId) { c ->
            val byId = changed.associateBy { it.deviceId }
            c.copy(receipts = c.receipts.map { byId[it.deviceId] ?: it } + changed.filter { n -> c.receipts.none { it.deviceId == n.deviceId } })
        }
        live(chatId).receipts.value = cached.receipts
    }

    /** When each member first saw and read [msgId]; throws the [RelayException] of a refused call (the Info dialog shows no times then). */
    suspend fun messageReceipts(chatId: String, msgId: String): List<MessageReceipt> = requireApi().messageReceipts(chatId, msgId)

    private fun isMember(cached: CachedChat) = me()?.let { id -> cached.members.any { it.deviceId == id } } == true

    /** What a successful sync owes the relay: the messages reached this device (seen), and are on screen if the chat is open (read). */
    private suspend fun afterSync(chatId: String) {
        if (!isMember(store.read(chatId))) return // a space admin looking in is never counted
        reportSeen(chatId)
        if (openChat.value == chatId) try { markRead(chatId) } catch (e: RelayException) { /* the next sync tries again */ }
    }

    /** Reports everything cached of [chatId] as seen unless that was done; a failure is retried by the next sync or refresh. */
    private suspend fun reportSeen(chatId: String) {
        val a = api() ?: return
        val gen = generation
        val cached = store.read(chatId)
        if (cached.rev == 0L || cached.seenRev >= cached.rev || !isMember(cached)) return
        try {
            a.markChatSeen(chatId, cached.rev)
            if (gen != generation) return
            store.update(chatId) { it.copy(seenRev = maxOf(it.seenRev, cached.rev)) }
        } catch (e: RelayException) { /* try again later */ }
    }

    /** Brings the chat's list entry up to date with what the sync found. */
    private fun refreshSummary(chatId: String, cached: CachedChat, renamed: String?) {
        val last = cached.messages.values.filter { !it.deleted }.maxByOrNull { it.createdRev ?: it.rev } // an edit keeps its place in the preview
        var name: String? = null
        _chats.update { list ->
            list.map { s ->
                if (s.id != chatId) s else s.copy(
                    name = (renamed ?: s.name).also { name = it },
                    ownerId = cached.ownerId,
                    rev = maxOf(s.rev, cached.rev), // a failed sync must not make a chat with history look empty
                    member = me()?.let { id -> cached.members.any { it.deviceId == id } } ?: s.member,
                    lastMessage = last,
                )
            }
        }
        name?.let { n -> if (n != cached.name) store.update(chatId) { it.copy(name = n) } }
        recountUnread()
    }

    /** Emits [messages] to [newMessages] unless the chat is open; system entries, deleted ones and our own never are. */
    private suspend fun announce(chatId: String, messages: Collection<ChatMessage>, cached: CachedChat, gen: Int) {
        if (openChat.value == chatId) return
        val summary = _chats.value.firstOrNull { it.id == chatId }
        if (!(summary?.member ?: isMember(cached))) return // a space admin overseeing a chat is not told of its messages
        val name = summary?.name ?: cached.name
        val readRev = summary?.readRev ?: cached.readRev
        val muted = summary?.muted == true
        val mine = me()
        for (m in messages.sortedBy { it.rev }) {
            if (m.event != null || m.deleted || m.author.deviceId == mine) continue
            val text = Messages.messagePreview(m).ifEmpty { null } ?: continue
            val mentionedMe = mine != null && mine in m.mentions
            if (muted && !mentionedMe) continue // a muted chat speaks only when mentioned
            if ((m.createdRev ?: m.rev) <= readRev && !mentionedMe) continue // read elsewhere (an edit does not make it unread); a mention still gets through
            if (gen != generation) return
            _newMessages.emit(IncomingMessage(chatId, name, m.author.deviceId, m.author.name, text, mentionedMe))
        }
    }

    /** Puts [cached] on screen. */
    private fun show(chatId: String, cached: CachedChat) {
        val l = live(chatId)
        l.messages.value = ordered(cached)
        l.members.value = cached.members
        l.receipts.value = cached.receipts
        recountUnread()
    }

    /** True once a [refresh] has listed the chats; until then the unread counts come from the cache alone. */
    @Volatile private var listed = false

    private fun recountUnread() {
        val mine = me()
        // (chat, read marker): the relay's list once we have it, else what the cache remembers.
        val chats = if (listed) _chats.value.filter { it.member }.map { it.id to it.readRev }
        else store.cachedIds().map { it to store.read(it).readRev }
        val mentioned = linkedSetOf<String>()
        _unread.value = buildMap {
            for ((id, readRev) in chats) {
                val unread = live(id).messages.value.filter { it.event == null && !it.deleted && it.author.deviceId != mine && (it.createdRev ?: it.rev) > readRev }
                if (unread.isNotEmpty()) put(id, unread.size)
                if (mine != null && unread.any { mine in it.mentions }) mentioned += id
            }
        }
        _unreadMentions.value = mentioned
    }

    init { recountUnread() } // from the cache, until a refresh brings the relay's list

    /** Drops everything about [chatId]: cache, list entry, invites. */
    private fun forget(chatId: String) {
        store.forget(chatId)
        live.remove(chatId)?.let { it.messages.value = emptyList(); it.members.value = emptyList(); it.receipts.value = emptyList() }
        _chats.update { list -> list.filter { it.id != chatId } }
        _invites.update { list -> list.filter { it.chatId != chatId } }
        openChat.compareAndSet(chatId, null)
        recountUnread()
    }

    /** Forgets everything: lists, messages, cache files and the open chat (this device was re-registered or moved to another relay). */
    fun clear() {
        generation++
        store.clearAll()
        live.values.forEach { it.messages.value = emptyList(); it.members.value = emptyList(); it.receipts.value = emptyList() }
        live.clear()
        syncLocks.clear()
        _chats.value = emptyList()
        _invites.value = emptyList()
        _unread.value = emptyMap()
        _unreadMentions.value = emptySet()
        openChat.value = null
        listed = false
        presenceTracker.clear()
        outbox?.clear()
        media?.clear()
        _pendingSends.value = emptyList()
    }

    private fun upsert(summary: ChatSummary) {
        if (summary.rev == 0L && summary.member) store.update(summary.id) { it.copy(listedEmpty = it.listedEmpty || it.rev == 0L) } // its first messages are news, even after a restart
        _chats.update { list -> if (list.any { it.id == summary.id }) list.map { if (it.id == summary.id) summary else it } else list + summary }
        recountUnread()
    }

    // ---- Live events ----

    /** Feeds a relay event in; the work runs on the controller's scope. */
    fun onEvent(e: RelayEvent) {
        if (e == RelayEvent.Connected) _connects.update { it + 1 }
        presenceTracker.onEvent(e)
        scope.launch {
            when (e) {
                is RelayEvent.ChatChanged -> chatChanged(e.chatId, e.rev, e.receipts)
                is RelayEvent.ChatDeleted -> forget(e.chatId)
                RelayEvent.ChatInvitesChanged -> refreshInvites()
                RelayEvent.Connected -> { resumeSends(); refresh(); refreshInvites() }
                is RelayEvent.SpaceChanged, is RelayEvent.SpaceDeleted -> { refresh(); refreshInvites() }
                else -> Unit
            }
        }
    }

    private suspend fun chatChanged(chatId: String, rev: Long, receipts: List<Receipt>) {
        if (receipts.isNotEmpty()) applyReceipts(chatId, receipts) // someone's seen or read marker moved: no sync needed for that
        if (_chats.value.none { it.id == chatId }) refreshAll(chatId to rev) // a chat we have not seen yet
        val have = store.read(chatId).rev
        if (have >= rev && _chats.value.any { it.id == chatId }) return
        syncChat(chatId, if (have == 0L) rev - 1 else null)
    }

    // ---- Actions ----

    suspend fun createChat(spaceId: String, name: String): ChatSummary = requireApi().createChat(spaceId, name).also(::upsert)

    /** Creates an open channel everyone in [spaceId] is in. */
    suspend fun createChannel(spaceId: String, name: String): ChatSummary = requireApi().createChannel(spaceId, name).also(::upsert)

    /** Mutes or unmutes [chatId] at once; if the relay refuses, the old state comes back and the failure is rethrown. */
    suspend fun setMuted(chatId: String, muted: Boolean) {
        val before = _chats.value.firstOrNull { it.id == chatId }?.muted
        fun put(value: Boolean) = _chats.update { list -> list.map { if (it.id == chatId) it.copy(muted = value) else it } }
        put(muted)
        try {
            upsert(requireApi().setChatMuted(chatId, muted))
        } catch (e: Exception) {
            if (before != null) put(before)
            throw e
        }
    }

    suspend fun renameChat(chatId: String, name: String) {
        upsert(requireApi().renameChat(chatId, name)); sync(chatId)
    }

    suspend fun deleteChat(chatId: String) {
        requireApi().deleteChat(chatId); forget(chatId)
    }

    suspend fun inviteToChat(chatId: String, deviceId: String): ChatInvite =
        requireApi().inviteToChat(chatId, deviceId).also { refreshInvites() }

    suspend fun approveInvite(id: String) { requireApi().approveInvite(id); refreshInvites() }
    suspend fun denyInvite(id: String) { requireApi().denyInvite(id); refreshInvites() }
    suspend fun declineInvite(id: String) { requireApi().declineInvite(id); refreshInvites() }

    suspend fun joinInvite(id: String) {
        val chat = requireApi().joinInvite(id)
        upsert(chat); refreshInvites()
        // Its history is not news, even if the sync below fails: what the join left is the watermark.
        store.update(chat.id) { it.copy(announcedRev = maxOf(it.announcedRev, chat.rev)) }
        syncChat(chat.id, Long.MAX_VALUE)
    }

    suspend fun transferChatOwner(chatId: String, deviceId: String) {
        upsert(requireApi().transferChatOwner(chatId, deviceId)); sync(chatId)
    }

    /** The space admin removes [deviceId] from the chat. */
    suspend fun removeChatMember(chatId: String, deviceId: String) {
        requireApi().removeChatMember(chatId, deviceId); sync(chatId)
    }

    /** This device leaves the chat (the owner has to hand over first: owner_must_transfer). */
    suspend fun leaveChat(chatId: String) {
        val id = me() ?: throw RelayException(RelayErrorKind.NETWORK, "not registered")
        requireApi().removeChatMember(chatId, id); sync(chatId) // the admin keeps seeing it; everyone else gets a 404 and forgets it
    }

    /** The space admin joins [chatId] without an invite. */
    suspend fun adminJoinChat(chatId: String) {
        upsert(requireApi().adminJoinChat(chatId)); sync(chatId)
    }

    /** Posts [text]; [mentions] are the device ids of chat members it mentions (see [outgoingMentions]). */
    suspend fun send(chatId: String, text: String, mentions: List<String> = emptyList()): ChatMessage =
        checked(text).let { requireApi().postMessage(chatId, text, mentions) }.also { keep(chatId, it) }

    /**
     * Sends [text] (optional) with [files]; a folder in [files] is sent as its files, keeping their paths. With no files
     * this is a plain post. Otherwise the send is queued (see [pendingSends]) and this returns: the files upload in the
     * background, resuming after a network loss or restart ([resumeSends]), and are posted once all are up, in messages of
     * at most 20 attachments with [text] and [mentions] on the first. [thumbnailer] makes the photo and GIF previews; a photo
     * it can't read goes as a file. Throws INVALID "empty" when there is nothing to send, "too_long" for text over the limit,
     * "too_many_files" (user message [Messages.tooManyFiles]) for a folder of more than [Limits.MAX_FILES_PER_SEND] files.
     * A file the relay refuses as too large is left out of the send and named in [sendNotices] (ruling CM-R7).
     */
    suspend fun send(chatId: String, text: String?, files: List<File>, thumbnailer: Thumbnailer, mentions: List<String> = emptyList()) {
        val body = text?.takeIf { it.isNotBlank() }?.also(::checked)
        val picked = withContext(Dispatchers.IO) {
            files.flatMap { f ->
                if (f.isDirectory) walkFolder(f).also { found ->
                    // Like a delivery, a folder over the per-send file limit is refused whole.
                    if (found.size > Limits.MAX_FILES_PER_SEND) {
                        throw RelayException(RelayErrorKind.INVALID, "too_many_files", userMessage = Messages.tooManyFiles(f.name))
                    }
                }.map { it to it.relativePath }
                else listOf(LocalUpload(f.absolutePath, f.name, mimeOf(f.name), f.length()) to null)
            }
        }
        if (picked.isEmpty()) {
            send(chatId, body ?: throw RelayException(RelayErrorKind.INVALID, "empty"), mentions)
            return
        }
        val ob = checkNotNull(outbox) { "this ChatController has no outbox for sending files" }
        val gen = generation
        val id = UUID.randomUUID().toString()
        val attachments = withContext(Dispatchers.IO) {
            picked.mapIndexed { i, (upload, relativePath) ->
                val info = mediaInfo(File(upload.path), upload.mimeType, thumbnailer)
                val thumb = info.thumb?.let { t ->
                    val f = ob.thumbFile(id, i).apply { parentFile.mkdirs(); writeBytes(t.jpeg) }
                    LocalUpload(f.absolutePath, f.name, "image/jpeg", f.length())
                }
                // Values the relay would refuse (a huge panorama, a bogus length) are left out rather than fail the send.
                val t = info.thumb
                val (w, h) = validSidePx(t?.width)?.let { w -> validSidePx(t?.height)?.let { h -> w to h } } ?: (null to null)
                OutgoingAttachment(upload, info.kind, w, h, validDurationMs(t?.durationMs), thumb, relativePath)
            }
        }
        if (gen != generation) { // cleared meanwhile: this send belonged to the old relay
            attachments.forEach { a -> a.thumb?.let { File(it.path).delete() } }
            return
        }
        val item = OutgoingMessage(id, chatId, body, if (body != null) mentions else emptyList(), attachments, System.currentTimeMillis())
        ob.enqueue(item)
        _pendingSends.update { it + pendingOf(item) }
        resumeSends()
    }

    /**
     * Uploads and posts the queued sends in order; one that can't go on for now (network) holds back the rest until the next
     * call. A rate-limited one is tried again by itself after [RATE_LIMIT_RETRY_MS].
     */
    fun resumeSends(): Job {
        val gen = generation
        return scope.launch { drainSends(gen) }.also { lastDrain = it }
    }

    /** The newest drain [resumeSends] launched, so [awaitSendsIdle] can wait for one that hasn't taken [sendLock] yet. */
    @Volatile private var lastDrain: Job? = null

    private suspend fun drainSends(gen: Int) {
        val ob = outbox ?: return
        sendLock.withLock {
            for (item in ob.pending().filter { it.failure == null }) { // a failed send waits for the user to dismiss it
                if (gen != generation) return
                val a = api() ?: return
                val dropped = mutableListOf<String>() // named together once this round ends, so none is overwritten
                val result = ob.process(
                    item.id, a,
                    onProgress = { done, _ -> if (gen == generation) updatePending(item.id) { it.copy(sentBytes = done) } },
                    onPosted = { m ->
                        if (gen == generation) {
                            keep(item.chatId, m)
                            updatePending(item.id) { it.copy(text = null, files = it.files.drop(m.attachments.size)) }
                        }
                    },
                    onDropped = { name ->
                        if (gen == generation) {
                            dropped += name
                            ob.get(item.id)?.let { fresh -> updatePending(item.id) { pendingOf(fresh) } }
                        }
                    },
                )
                if (gen != generation) return
                if (dropped.isNotEmpty()) _sendNotices.tryEmit(Messages.tooBigForRelay(dropped))
                when (result) {
                    is ChatOutboxResult.Done -> {
                        _pendingSends.update { list -> list.filter { it.id != item.id } }
                        if (result.unconfirmed) sync(item.chatId)
                    }
                    ChatOutboxResult.Cancelled -> _pendingSends.update { list -> list.filter { it.id != item.id } }
                    ChatOutboxResult.Retry -> return
                    ChatOutboxResult.RateLimited -> {
                        rateLimitRetry?.cancel()
                        rateLimitRetry = scope.launch {
                            delay(RATE_LIMIT_RETRY_MS)
                            if (gen == generation) drainSends(gen)
                        }
                        return
                    }
                    is ChatOutboxResult.Failed -> updatePending(item.id) { it.copy(failure = result.message) }
                }
            }
        }
    }

    /** Waits for the sends being uploaded and posted right now (tests). */
    internal suspend fun awaitSendsIdle() {
        // A drain launched just now may not hold the lock yet; wait for it (and any it started) before taking the lock.
        while (true) {
            val job = lastDrain ?: break
            job.join()
            if (lastDrain === job) break
        }
        sendLock.withLock { }
    }

    /**
     * *Cancel* on a send still uploading or waiting, *Dismiss* on a failed one: removes it from [pendingSends] and the outbox
     * with what it had not posted and its thumbnails. An upload in progress stops at its next chunk; nothing more is posted.
     */
    fun dismissSend(id: String) {
        outbox?.remove(id)
        _pendingSends.update { list -> list.filter { it.id != id } }
    }

    private fun updatePending(id: String, change: (PendingSend) -> PendingSend) {
        _pendingSends.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun pendingOf(item: OutgoingMessage): PendingSend {
        val rest = item.attachments.drop(item.posted)
        return PendingSend(
            id = item.id, chatId = item.chatId, text = item.text.takeIf { item.posted == 0 },
            files = rest.map { PendingFile(it.name, it.kind, it.upload.sizeBytes, it.relativePath, it.upload.path, it.thumb?.path) },
            sentBytes = item.attachments.take(item.posted).sumOf { it.totalBytes } + rest.sumOf { it.uploadedBytes },
            totalBytes = item.attachments.sumOf { it.totalBytes },
            failure = item.failure,
        )
    }

    /**
     * Saves [att] of a message in [chatId] to [target], from the media cache or downloaded into it first. Nothing is left at
     * [target] on failure. A deleted message's file fails with NOT_FOUND "deleted" (user message [Messages.MESSAGE_DELETED])
     * and the chat is synced to show the deletion. Local I/O failures (disk full) throw their IOException.
     */
    suspend fun download(chatId: String, att: Attachment, target: File) {
        val cache = checkNotNull(media) { "this ChatController has no media cache for downloads" }
        val a = requireApi()
        cache.pin(att.fileId) // not evicted while being copied
        try {
            val cached = try {
                cache.get(att, a)
            } catch (e: RelayException) {
                if (e.kind != RelayErrorKind.NOT_FOUND) throw e
                sync(chatId)
                throw RelayException(RelayErrorKind.NOT_FOUND, "deleted", e, userMessage = Messages.MESSAGE_DELETED)
            }
            withContext(Dispatchers.IO) {
                val part = File(target.absoluteFile.parentFile, ".${target.name}.part")
                try {
                    cached.copyTo(part, overwrite = true)
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Throwable) {
                    part.delete()
                    throw e
                }
            }
        } finally {
            cache.unpin(att.fileId)
        }
    }

    suspend fun edit(chatId: String, msgId: String, text: String, mentions: List<String> = emptyList()): ChatMessage =
        checked(text).let { requireApi().editMessage(chatId, msgId, text, mentions) }.also { keep(chatId, it) }

    suspend fun delete(chatId: String, msgId: String) {
        requireApi().deleteMessage(chatId, msgId); sync(chatId)
    }

    /** Marks everything this device has of [chatId] as read, here and on the relay. */
    suspend fun markRead(chatId: String) {
        val rev = store.read(chatId).rev
        val summary = _chats.value.firstOrNull { it.id == chatId }
        if (rev == 0L || (summary != null && (!summary.member || summary.readRev >= rev))) return
        val gen = generation
        requireApi().markChatRead(chatId, rev)
        if (gen != generation) return
        store.update(chatId) { it.copy(readRev = maxOf(it.readRev, rev)) }
        _chats.update { list -> list.map { if (it.id == chatId) it.copy(readRev = maxOf(it.readRev, rev)) else it } }
        recountUnread()
    }

    /** The relay's limits, checked up front: not blank, at most 4,000 characters counted as code points. */
    private fun checked(text: String) {
        if (text.isBlank()) throw RelayException(RelayErrorKind.INVALID, "empty")
        if (text.codePointCount(0, text.length) > Limits.MAX_CHAT_TEXT_LENGTH) throw RelayException(RelayErrorKind.INVALID, "too_long")
    }

    /** Shows our own post or edit at once. The cache's rev stays, so the next sync still delivers everything in between. */
    private fun keep(chatId: String, message: ChatMessage) {
        show(chatId, store.update(chatId) { it.copy(messages = it.messages + (message.id to message)) })
    }

    companion object {
        /** How long a rate-limited send waits before it is tried again. */
        const val RATE_LIMIT_RETRY_MS = 60_000L
    }
}
