package app.mjolnir.android.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mjolnir.client.ChatController
import app.mjolnir.client.Messages
import app.mjolnir.client.MentionPicks
import app.mjolnir.client.capCodePoints
import app.mjolnir.client.insertMention
import app.mjolnir.client.latestOwnMessage
import app.mjolnir.client.mentionSpans
import app.mjolnir.client.receiptLineFor
import app.mjolnir.client.Nicknames
import app.mjolnir.client.RelayApi
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.InviteState
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.MessageReceipt
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Receipt
import app.mjolnir.protocol.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One line of the chat list. */
data class ChatRow(val chat: ChatSummary, val unread: Int, val preview: String, val lastAtEpochMs: Long?, val mention: Boolean = false)

/** The chats of one space, most recently active first. */
data class ChatGroup(val spaceName: String, val rows: List<ChatRow>)

/** The chat list: approvals this phone owes as an owner on top, invitations to join below. */
data class ChatListUi(
    val groups: List<ChatGroup> = emptyList(),
    val approvals: List<ChatInvite> = emptyList(),
    val invitations: List<ChatInvite> = emptyList(),
)

/** What the long-press sheet offers on a message. */
enum class MessageAction { SHARE, INFO, EDIT, DELETE }

/** Own messages: Share…, Info, Edit, Delete. Others': Share…, plus Delete for the space admin. Deleted messages and system lines offer nothing. */
fun messageActions(m: ChatMessage, selfId: String?, isSpaceAdmin: Boolean): List<MessageAction> {
    if (m.deleted || m.event != null) return emptyList()
    val mine = m.author.deviceId == selfId
    return buildList {
        add(MessageAction.SHARE)
        if (mine) add(MessageAction.INFO)
        if (mine) add(MessageAction.EDIT)
        if (mine || isSpaceAdmin) add(MessageAction.DELETE)
    }
}

/** The text a bubble shows: the message, or why it is gone. */
fun bodyText(m: ChatMessage): String = when {
    m.deleted -> if (m.deletedByAdmin) Messages.DELETED_BY_ADMIN else Messages.MESSAGE_DELETED
    else -> m.text.orEmpty()
}

private const val KEY_CHAT = "chat"
private const val KEY_EDITING = "editing"
private fun draftKey(chatId: String) = "draft/$chatId"
private fun stashKey(chatId: String) = "stash/$chatId"
private fun picksKey(chatId: String) = "picks/$chatId"

/**
 * The phone's chats: the list, the open chat with its composer, and the chat settings actions. The chat open on screen
 * and every draft live in [savedState], so a rotation (or the system recreating the process) keeps them.
 *
 * The chat counts as *open* (no notification, messages read) only while the app is in the [foreground]. The controller
 * itself is app-wide and lives in the AppContainer; this model only drives what the screens show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val controller: ChatController,
    private val api: RelayApi,
    private val nicknames: Nicknames,
    private val selfId: () -> String?,
    private val foreground: StateFlow<Boolean>,
    /** True while the Chats tab is the screen shown: the selected chat survives a tab switch, but is not on screen then. */
    private val chatsVisible: StateFlow<Boolean>,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    /** Called when [chatId] is read on screen (the app drops its notification). */
    private val onRead: (chatId: String) -> Unit = {},
) : ViewModel() {
    private val _spaces = MutableStateFlow<List<Space>>(emptyList())
    /** The spaces this phone is in: who administers them, who could be invited, where a new chat can go. */
    val spaces: StateFlow<List<Space>> = _spaces.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** True once the chat list was fetched at least once since this screen came up (whether or not it worked). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var sending = false

    private val _notice = MutableStateFlow<String?>(null)
    /** The last failed chat action, as a sentence; null once dismissed. */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun dismissNotice() { _notice.value = null }

    val selectedId: StateFlow<String?> = savedState.getStateFlow<String?>(KEY_CHAT, null)

    /** The id of the message being edited, if any. */
    val editingId: StateFlow<String?> = savedState.getStateFlow<String?>(KEY_EDITING, null)

    val list: StateFlow<ChatListUi> = combine(controller.chats, controller.invites, controller.unread, nicknames.changes, controller.unreadMentions) { chats, invites, unread, names, mentions ->
        fun nameOf(p: Peer) = names[p.deviceId] ?: p.name
        val rows = chats.map { chat ->
            val last = chat.lastMessage
            val preview = when {
                last == null -> "No messages yet"
                last.event != null -> Messages.systemLine(last.event!!) { id -> peerName(id, chat, names) }
                else -> "${nameOf(last.author)}: ${Messages.messagePreview(last)}"
            }
            ChatRow(chat, unread[chat.id] ?: 0, preview, last?.sentAtEpochMs, chat.id in mentions)
        }
        val groups = rows.groupBy { it.chat.spaceId }.values
            .sortedBy { it.first().chat.spaceName.lowercase() }
            .map { g -> ChatGroup(g.first().chat.spaceName, g.sortedWith(compareByDescending<ChatRow> { it.lastAtEpochMs ?: 0L }.thenBy { it.chat.name.lowercase() })) }
        ChatListUi(groups, invites.filter { it.state == InviteState.PENDING_APPROVAL }, invites.filter { it.state == InviteState.APPROVED })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatListUi())

    /** The open chat's list entry, null when none is open or it is gone. */
    val chat: StateFlow<ChatSummary?> = combine(selectedId, controller.chats) { id, chats -> chats.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The open chat's messages, system entries included. */
    val messages: StateFlow<List<ChatMessage>> = selectedId.flatMapLatest { id -> id?.let(controller::messages) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val members: StateFlow<List<Peer>> = selectedId.flatMapLatest { id -> id?.let(controller::members) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Who has seen and read how much of the open chat. */
    val receipts: StateFlow<List<Receipt>> = selectedId.flatMapLatest { id -> id?.let(controller::receipts) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Members of the open chat that [query] (typed after an `@`) could mean, matched on their shown names. */
    fun mentionCandidates(query: String): List<Peer> =
        selectedId.value?.let { controller.mentionCandidates(it, query, ::nameOf) }.orEmpty()

    private fun picks(chatId: String) = MentionPicks(savedState.get<ArrayList<String>>(picksKey(chatId)).orEmpty())

    /** [peer] was picked from the @ picker: their shown name replaces what was typed after the `@`, and the mention is recorded. */
    fun pickMention(chatId: String, peer: Peer) {
        val draft = savedState.get<String>(draftKey(chatId)).orEmpty()
        setDraft(chatId, insertMention(draft, nameOf(peer)))
        savedState[picksKey(chatId)] = ArrayList(picks(chatId).with(peer.deviceId).ids)
    }

    /** When each member first saw and read [m], for its Info; empty when that can't be fetched. */
    suspend fun receiptTimes(m: ChatMessage): List<MessageReceipt> =
        try { controller.messageReceipts(m.chatId, m.id) } catch (e: CancellationException) { throw e } catch (e: RelayException) { emptyList() }

    /** The line under the open chat's latest own message, empty while nobody has seen it. */
    fun receiptLine(): String {
        val latest = latestOwnMessage(messages.value, selfId()) ?: return ""
        return receiptLineFor(latest, receipts.value, members.value, ::nameOf)
    }

    /** Private nicknames by device id. */
    val nicknameMap: StateFlow<Map<String, String>> = nicknames.changes

    val selfDeviceId: String? get() = selfId()

    init {
        viewModelScope.launch {
            var known = emptySet<String>()
            controller.chats.collect { chats -> known = forgetWhenGone(known, chats.mapTo(HashSet()) { it.id }) }
        }
        // The chat on screen is open (read, no notification) while the app is in the foreground; its messages are read as they arrive.
        viewModelScope.launch {
            combine(selectedId, foreground, chatsVisible) { id, fg, shown -> id.takeIf { fg && shown } }.distinctUntilChanged().collectLatest { id ->
                controller.openChat.value = id
                if (id != null) {
                    quietly { controller.sync(id) }
                    controller.messages(id).map { it.lastOrNull()?.rev }.distinctUntilChanged().collect {
                        quietly { controller.markRead(id) }
                        onRead(id)
                    }
                }
            }
        }
    }

    /** A chat that left the list (deleted, removed from it, or everything reset): closes it if open and drops its draft, stash and picks. */
    private fun forgetWhenGone(previous: Set<String>, now: Set<String>): Set<String> {
        for (gone in previous - now) {
            if (selectedId.value == gone) { savedState[KEY_EDITING] = null; savedState[KEY_CHAT] = null }
            listOf(draftKey(gone), stashKey(gone), picksKey(gone)).forEach { savedState.remove<Any?>(it) }
        }
        return now
    }

    private fun peerName(id: String, chat: ChatSummary, names: Map<String, String>): String =
        names[id] ?: chat.lastMessage?.author?.takeIf { it.deviceId == id }?.name
            ?: controller.members(chat.id).value.firstOrNull { it.deviceId == id }?.name ?: "Someone"

    /** Shown name of a device id of an event or message in the open chat. */
    fun nameOf(id: String): String =
        nicknames.get(id) ?: members.value.firstOrNull { it.deviceId == id }?.name
            ?: messages.value.firstOrNull { it.author.deviceId == id }?.author?.name ?: "Someone"

    fun nameOf(peer: Peer): String = nicknames.get(peer.deviceId) ?: peer.name

    fun isSpaceAdmin(chat: ChatSummary): Boolean = _spaces.value.any { it.id == chat.spaceId && it.adminId == selfId() }

    // ---- Reading ----

    /** Refreshes the list, the invites and the spaces; a failure keeps what is shown. */
    fun refresh() {
        viewModelScope.launch {
            quietly { controller.refresh() }
            quietly { controller.refreshInvites() }
            quietly { _spaces.value = api.spaces() }
            _loaded.value = true
        }
    }

    // ---- Opening and the composer ----

    fun open(chatId: String) { savedState[KEY_CHAT] = chatId }

    fun close() {
        if (editingId.value != null) cancelEdit() // the unsent draft comes back, not the old message's text
        savedState[KEY_CHAT] = null
    }

    fun draft(chatId: String): StateFlow<String> = savedState.getStateFlow(draftKey(chatId), "")

    fun setDraft(chatId: String, text: String) { savedState[draftKey(chatId)] = capCodePoints(text, Limits.MAX_CHAT_TEXT_LENGTH) }

    /** Edits one of our own messages: the composer shows its text, and the unsent draft comes back afterwards. */
    fun startEdit(m: ChatMessage) {
        val text = mentionSpans(m.text ?: return, m.mentions, members.value, ::nameOf).text // as shown here: mentions by nickname
        if (savedState.get<String?>(KEY_EDITING) == null) savedState[stashKey(m.chatId)] = savedState.get<String>(draftKey(m.chatId)).orEmpty()
        savedState[KEY_EDITING] = m.id
        savedState[draftKey(m.chatId)] = text
        savedState[picksKey(m.chatId)] = ArrayList(MentionPicks.of(m).ids)
    }

    fun cancelEdit() {
        val chatId = selectedId.value ?: return
        savedState[KEY_EDITING] = null
        savedState[draftKey(chatId)] = savedState.get<String>(stashKey(chatId)).orEmpty()
        savedState[picksKey(chatId)] = ArrayList<String>()
    }

    /** Sends the draft (or saves the edit); on a refusal the draft stays and the reason becomes the [notice]. */
    fun send() {
        val chatId = selectedId.value ?: return
        val text = savedState.get<String>(draftKey(chatId)).orEmpty()
        if (text.isBlank()) return
        if (sending) return // a double tap
        sending = true
        val editing = savedState.get<String?>(KEY_EDITING)
        // Only members picked from the picker are mentions; the wire carries their own name and device id.
        val (wire, mentions) = picks(chatId).outgoing(text.trim(), members.value, selfId(), ::nameOf)
        viewModelScope.launch {
            try {
                val ok = call { if (editing != null) controller.edit(chatId, editing, wire, mentions) else controller.send(chatId, wire, mentions) } != null
                if (!ok) return@launch
                if (editing != null) cancelEdit() else { savedState[draftKey(chatId)] = ""; savedState[picksKey(chatId)] = ArrayList<String>() }
            } finally {
                sending = false
            }
        }
    }

    fun delete(m: ChatMessage) = run { controller.delete(m.chatId, m.id) }

    // ---- Invites ----

    fun approve(invite: ChatInvite) = run { controller.approveInvite(invite.id) }
    fun deny(invite: ChatInvite) = run { controller.denyInvite(invite.id) }
    fun decline(invite: ChatInvite) = run { controller.declineInvite(invite.id) }

    fun join(invite: ChatInvite) {
        viewModelScope.launch { if (call { controller.joinInvite(invite.id) } != null) open(invite.chatId) }
    }

    // ---- Chats ----

    /** Creates a chat in [space] and opens it. */
    fun createChat(space: Space, name: String) {
        viewModelScope.launch { call { controller.createChat(space.id, name.trim()) }?.let { open(it.id) } }
    }

    fun rename(chatId: String, name: String) = run { controller.renameChat(chatId, name.trim()) }
    fun makeOwner(chatId: String, deviceId: String) = run { controller.transferChatOwner(chatId, deviceId) }
    fun removeMember(chatId: String, deviceId: String) = run { controller.removeChatMember(chatId, deviceId) }
    fun invite(chatId: String, deviceId: String) = run { controller.inviteToChat(chatId, deviceId) }
    fun adminJoin(chatId: String) = run { controller.adminJoinChat(chatId) }

    fun leave(chatId: String) {
        viewModelScope.launch { if (call { controller.leaveChat(chatId) } != null) closeIfOpen(chatId) }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch { if (call { controller.deleteChat(chatId) } != null) closeIfOpen(chatId) }
    }

    private fun closeIfOpen(chatId: String) { if (selectedId.value == chatId) close() }

    private fun run(block: suspend () -> Unit) { viewModelScope.launch { call { block(); Unit } } }

    /** The result of [block], or null after a refusal that became the [notice]. */
    private suspend fun <T> call(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RelayException) {
        _notice.value = Messages.chatError(e)
        null
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: RelayException) { /* keep what is shown */ }
    }
}
