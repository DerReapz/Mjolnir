package app.mjolnir.client

import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
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

/** A message by someone else in a chat that isn't open: what the apps turn into a notification (see [Messages.chatNotification]). */
data class IncomingMessage(val chatId: String, val chatName: String, val authorId: String, val authorName: String, val text: String)

/**
 * The chat side of the client, shared by desktop and Android: the chat list across all of the device's spaces, the
 * invites it can act on, and each chat's messages, cached on disk in [store] and kept current by [sync].
 *
 * User actions ([send], [createChat], ...) throw the [RelayException] of a refused call; the relay's code is its message.
 * Background work ([refresh], [sync], [onEvent]) swallows network failures and keeps what it has.
 */
class ChatController(
    private val api: () -> RelayApi?,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val me: () -> String?,
) {
    private class Live(val messages: MutableStateFlow<List<ChatMessage>>, val members: MutableStateFlow<List<Peer>>)

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

    /** The chat on screen, if any: its incoming messages don't go to [newMessages]. */
    val openChat = MutableStateFlow<String?>(null)

    private val _newMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    /**
     * Messages by others in chats that aren't open, for notifications. Start collecting before calling [refresh] or
     * [sync]: with no collector an emission is dropped. A slow collector holds the sync back (nothing is lost in bursts).
     */
    val newMessages: SharedFlow<IncomingMessage> = _newMessages.asSharedFlow()

    private val live = ConcurrentHashMap<String, Live>()
    private val syncLocks = ConcurrentHashMap<String, Mutex>()

    private fun live(chatId: String): Live = live.getOrPut(chatId) {
        val cached = store.read(chatId)
        Live(MutableStateFlow(ordered(cached)), MutableStateFlow(cached.members))
    }

    /** Messages oldest first by send time: an edit gets a new rev but keeps its place. */
    private fun ordered(c: CachedChat) = c.messages.values.sortedWith(compareBy({ it.sentAtEpochMs }, { it.id }))

    /** The messages of [chatId] from the cache, kept current by [sync]; system entries (see [ChatMessage.event]) included. */
    fun messages(chatId: String): StateFlow<List<ChatMessage>> = live(chatId).messages.asStateFlow()

    fun members(chatId: String): StateFlow<List<Peer>> = live(chatId).members.asStateFlow()

    private fun requireApi(): RelayApi = api() ?: throw RelayException(RelayErrorKind.NETWORK, "not connected")

    // ---- Reading ----

    /** Re-reads the chat list of every space, forgets chats that are gone, and syncs those that moved on. */
    suspend fun refresh() = refreshAll(null)

    /** [trigger] is the chat and rev of the `ChatChanged` that caused this refresh: its messages from that rev on are news. */
    private suspend fun refreshAll(trigger: Pair<String, Long>?) {
        val a = api() ?: return
        val summaries = try {
            a.spaces().flatMap { space ->
                try { a.chats(space.id) } catch (e: RelayException) { if (e.kind == RelayErrorKind.NOT_FOUND) emptyList() else throw e }
            }
        } catch (e: RelayException) {
            return
        }
        val ids = summaries.mapTo(HashSet()) { it.id }
        (_chats.value.map { it.id } + store.cachedIds()).filter { it !in ids }.toSet().forEach(::forget)
        for (s in summaries) {
            val c = store.read(s.id)
            if (c.name != s.name || c.readRev < s.readRev) store.update(s.id) { it.copy(name = s.name, readRev = maxOf(it.readRev, s.readRev)) }
        }
        _chats.value = summaries
        listed = true
        recountUnread()
        for (s in summaries) {
            val have = store.read(s.id).rev
            if (have >= s.rev) continue
            // With a cache, everything above it is news. Without one it is history, except what the triggering event announced.
            val floor = when {
                have > 0 -> null
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
        syncLocks.getOrPut(chatId) { Mutex() }.withLock {
            val before = store.read(chatId)
            val hasHistory = before.rev > 0 || _chats.value.firstOrNull { it.id == chatId }?.rev == 0L
            val start = floor ?: if (hasHistory) before.announcedRev else Long.MAX_VALUE
            // New to the user: above the start, and not an edit of something already announced.
            fun fresh(m: ChatMessage) = m.rev > start && (before.messages[m.id]?.let { it.rev > before.announcedRev } ?: true)
            var since = before.rev
            var latest = before
            var renamed: String? = null
            try {
                announce(chatId, before.messages.values.filter(::fresh), before) // fetched earlier, never announced
                while (true) {
                    val page = a.syncChat(chatId, since)
                    val last = page.messages.maxOfOrNull { it.rev }
                    val done = !page.hasMore || last == null || last <= since
                    // A page cut short keeps the rev of its last message, so an interrupted sync resumes where it stopped.
                    latest = store.update(chatId) { c ->
                        c.copy(
                            rev = if (done) page.rev else last!!,
                            ownerId = page.ownerId,
                            members = page.members,
                            messages = c.messages + page.messages.associateBy { it.id },
                        )
                    }
                    (page.messages.filter { it.event is ChatEvent.Renamed }.maxByOrNull { it.rev }?.event as? ChatEvent.Renamed)?.let { renamed = it.name }
                    show(chatId, latest)
                    announce(chatId, page.messages.filter(::fresh).map { latest.messages.getValue(it.id) }, latest)
                    // Only now, so a failure before this point leaves the page's messages to be announced on the retry.
                    val upTo = if (done) page.rev else last!!
                    latest = store.update(chatId) { it.copy(announcedRev = maxOf(it.announcedRev, upTo)) }
                    if (done) break
                    since = last!!
                }
            } catch (e: RelayException) {
                if (e.kind == RelayErrorKind.NOT_FOUND) { forget(chatId); return }
            }
            refreshSummary(chatId, latest, renamed)
        }
    }

    /** Brings the chat's list entry up to date with what the sync found. */
    private fun refreshSummary(chatId: String, cached: CachedChat, renamed: String?) {
        val last = cached.messages.values.filter { !it.deleted }.maxByOrNull { it.rev }
        var name: String? = null
        _chats.update { list ->
            list.map { s ->
                if (s.id != chatId) s else s.copy(
                    name = (renamed ?: s.name).also { name = it },
                    ownerId = cached.ownerId,
                    rev = cached.rev,
                    member = me()?.let { id -> cached.members.any { it.deviceId == id } } ?: s.member,
                    lastMessage = last,
                )
            }
        }
        name?.let { n -> if (n != cached.name) store.update(chatId) { it.copy(name = n) } }
        recountUnread()
    }

    /** Emits [messages] to [newMessages] unless the chat is open; system entries, deleted ones and our own never are. */
    private suspend fun announce(chatId: String, messages: Collection<ChatMessage>, cached: CachedChat) {
        if (openChat.value == chatId) return
        val summary = _chats.value.firstOrNull { it.id == chatId }
        val name = summary?.name ?: cached.name
        val readRev = summary?.readRev ?: cached.readRev
        val mine = me()
        for (m in messages.sortedBy { it.rev }) {
            val text = m.text ?: continue
            if (m.event != null || m.deleted || m.author.deviceId == mine || m.rev <= readRev) continue
            _newMessages.emit(IncomingMessage(chatId, name, m.author.deviceId, m.author.name, text))
        }
    }

    /** Puts [cached] on screen. */
    private fun show(chatId: String, cached: CachedChat) {
        val l = live(chatId)
        l.messages.value = ordered(cached)
        l.members.value = cached.members
        recountUnread()
    }

    /** True once a [refresh] has listed the chats; until then the unread counts come from the cache alone. */
    @Volatile private var listed = false

    private fun recountUnread() {
        val mine = me()
        // (chat, read marker): the relay's list once we have it, else what the cache remembers.
        val chats = if (listed) _chats.value.filter { it.member }.map { it.id to it.readRev }
        else store.cachedIds().map { it to store.read(it).readRev }
        _unread.value = buildMap {
            for ((id, readRev) in chats) {
                val n = live(id).messages.value.count { it.event == null && !it.deleted && it.author.deviceId != mine && it.rev > readRev }
                if (n > 0) put(id, n)
            }
        }
    }

    init { recountUnread() } // from the cache, until a refresh brings the relay's list

    /** Drops everything about [chatId]: cache, list entry, invites. */
    private fun forget(chatId: String) {
        store.forget(chatId)
        live.remove(chatId)?.let { it.messages.value = emptyList(); it.members.value = emptyList() }
        _chats.update { list -> list.filter { it.id != chatId } }
        _invites.update { list -> list.filter { it.chatId != chatId } }
        openChat.compareAndSet(chatId, null)
        recountUnread()
    }

    private fun upsert(summary: ChatSummary) {
        _chats.update { list -> if (list.any { it.id == summary.id }) list.map { if (it.id == summary.id) summary else it } else list + summary }
        recountUnread()
    }

    // ---- Live events ----

    /** Feeds a relay event in; the work runs on the controller's scope. */
    fun onEvent(e: RelayEvent) {
        scope.launch {
            when (e) {
                is RelayEvent.ChatChanged -> chatChanged(e.chatId, e.rev)
                is RelayEvent.ChatDeleted -> forget(e.chatId)
                RelayEvent.ChatInvitesChanged -> refreshInvites()
                RelayEvent.Connected, is RelayEvent.SpaceChanged, is RelayEvent.SpaceDeleted -> { refresh(); refreshInvites() }
                else -> Unit
            }
        }
    }

    private suspend fun chatChanged(chatId: String, rev: Long) {
        if (_chats.value.none { it.id == chatId }) refreshAll(chatId to rev) // a chat we have not seen yet
        val have = store.read(chatId).rev
        if (have >= rev && _chats.value.any { it.id == chatId }) return
        syncChat(chatId, if (have == 0L) rev - 1 else null)
    }

    // ---- Actions ----

    suspend fun createChat(spaceId: String, name: String): ChatSummary = requireApi().createChat(spaceId, name).also(::upsert)

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
        upsert(chat); refreshInvites(); syncChat(chat.id, Long.MAX_VALUE) // its history is not news
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

    suspend fun send(chatId: String, text: String): ChatMessage = checked(text).let { requireApi().postMessage(chatId, text) }.also { keep(chatId, it) }

    suspend fun edit(chatId: String, msgId: String, text: String): ChatMessage =
        checked(text).let { requireApi().editMessage(chatId, msgId, text) }.also { keep(chatId, it) }

    suspend fun delete(chatId: String, msgId: String) {
        requireApi().deleteMessage(chatId, msgId); sync(chatId)
    }

    /** Marks everything this device has of [chatId] as read, here and on the relay. */
    suspend fun markRead(chatId: String) {
        val rev = store.read(chatId).rev
        val summary = _chats.value.firstOrNull { it.id == chatId }
        if (rev == 0L || (summary != null && (!summary.member || summary.readRev >= rev))) return
        requireApi().markChatRead(chatId, rev)
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
}
