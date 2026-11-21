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
data class IncomingMessage(val chatId: String, val chatName: String, val authorName: String, val text: String)

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
    suspend fun refresh() {
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
        _chats.value = summaries
        recountUnread()
        for (s in summaries) {
            val have = store.read(s.id).rev
            // A chat seen for the first time is history, not news.
            if (have < s.rev) syncChat(s.id, notify = have > 0)
        }
    }

    suspend fun refreshInvites() {
        val a = api() ?: return
        try { _invites.value = a.chatInvites() } catch (e: RelayException) { /* keep the list */ }
    }

    /** Fetches what [chatId] gained since the cache's rev, page after page; a NOT_FOUND answer forgets the chat. */
    suspend fun sync(chatId: String) = syncChat(chatId, notify = true)

    private suspend fun syncChat(chatId: String, notify: Boolean) {
        val a = api() ?: return
        syncLocks.getOrPut(chatId) { Mutex() }.withLock {
            val before = store.read(chatId)
            val incoming = ArrayList<ChatMessage>()
            var since = before.rev
            var latest = before
            try {
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
                    incoming += page.messages.filter { it.id !in before.messages }
                    show(chatId, latest)
                    if (done) break
                    since = last!!
                }
            } catch (e: RelayException) {
                if (e.kind == RelayErrorKind.NOT_FOUND) forget(chatId)
                return
            }
            refreshSummary(chatId, latest, incoming)
            if (notify) announce(chatId, incoming)
        }
    }

    /** Brings the chat's list entry up to date with what the sync found. */
    private fun refreshSummary(chatId: String, cached: CachedChat, incoming: List<ChatMessage>) {
        val renamed = incoming.filter { it.event is ChatEvent.Renamed }.maxByOrNull { it.rev }?.event as? ChatEvent.Renamed
        val last = cached.messages.values.filter { !it.deleted }.maxByOrNull { it.rev }
        _chats.update { list ->
            list.map { s ->
                if (s.id != chatId) s else s.copy(
                    name = renamed?.name ?: s.name,
                    ownerId = cached.ownerId,
                    rev = cached.rev,
                    member = me()?.let { id -> cached.members.any { it.deviceId == id } } ?: s.member,
                    lastMessage = last,
                )
            }
        }
        recountUnread()
    }

    private fun announce(chatId: String, incoming: List<ChatMessage>) {
        if (openChat.value == chatId) return
        val summary = _chats.value.firstOrNull { it.id == chatId }
        val readRev = summary?.readRev ?: 0
        val mine = me()
        for (m in incoming.sortedBy { it.rev }) {
            val text = m.text ?: continue
            if (m.event != null || m.deleted || m.author.deviceId == mine || m.rev <= readRev) continue
            _newMessages.tryEmit(IncomingMessage(chatId, summary?.name.orEmpty(), m.author.name, text))
        }
    }

    /** Puts [cached] on screen. */
    private fun show(chatId: String, cached: CachedChat) {
        val l = live(chatId)
        l.messages.value = ordered(cached)
        l.members.value = cached.members
        recountUnread()
    }

    private fun recountUnread() {
        val mine = me()
        _unread.value = buildMap {
            for (s in _chats.value) {
                if (!s.member) continue
                val n = live(s.id).messages.value.count { it.event == null && !it.deleted && it.author.deviceId != mine && it.rev > s.readRev }
                if (n > 0) put(s.id, n)
            }
        }
    }

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
        if (_chats.value.none { it.id == chatId }) refresh() // a chat we have not seen yet
        if (store.read(chatId).rev >= rev && _chats.value.any { it.id == chatId }) return
        sync(chatId)
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
        upsert(chat); refreshInvites(); syncChat(chat.id, notify = false)
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
