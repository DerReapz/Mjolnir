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
import app.mjolnir.android.chat.ChatImport
import app.mjolnir.android.chat.ChatMediaSupport
import app.mjolnir.client.PendingSend
import app.mjolnir.client.ReceivedPath
import app.mjolnir.client.uniqueName
import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.safeFileName
import app.mjolnir.protocol.safeRelativePath
import app.mjolnir.protocol.topFolder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
enum class MessageAction { DOWNLOAD, SHARE, INFO, EDIT, DELETE }

/**
 * Own messages: Share…, Info, Edit (when there is text), Delete. Others': Share…, plus Delete for the space admin. A message
 * with attachments offers Download first. Deleted messages and system lines offer nothing.
 */
fun messageActions(m: ChatMessage, selfId: String?, isSpaceAdmin: Boolean): List<MessageAction> {
    if (m.deleted || m.event != null) return emptyList()
    val mine = m.author.deviceId == selfId
    return buildList {
        if (m.attachments.isNotEmpty()) add(MessageAction.DOWNLOAD)
        add(MessageAction.SHARE)
        if (mine) add(MessageAction.INFO)
        if (mine && !m.text.isNullOrBlank()) add(MessageAction.EDIT)
        if (mine || isSpaceAdmin) add(MessageAction.DELETE)
    }
}

/** The long-press sheet's first line (before "· from …"): the text, else what is attached ("Video · 18 MB", "2 files · 3 MB"). */
fun sheetSummary(m: ChatMessage): String {
    m.text?.takeIf { it.isNotBlank() }?.let { return it.replace('\n', ' ') }
    val single = m.attachments.singleOrNull()
    return if (single != null) Messages.fileBubbleDetail(single.kind, single.sizeBytes)
    else "${m.attachments.size} files · ${Messages.size(m.attachments.sumOf { it.sizeBytes })}"
}

/** Content another app shared, waiting for the user to pick a chat for it, or Throw ([throwInstead]). [uris] are `content://` strings. */
class SharedContent(val text: String?, val uris: List<String>, val throwInstead: () -> Unit)

/** The "Send to a chat" picker: [content] and the chats this phone can write in. */
class ShareOffer(val content: SharedContent, val chats: List<ChatSummary>)

/** Files ready for Share…: each with its media type, plus the message's text. */
data class ShareRequest(val files: List<Pair<File, String>>, val text: String?)

/** The text a bubble shows: the message, or why it is gone. */
fun bodyText(m: ChatMessage): String = when {
    m.deleted -> if (m.deletedByAdmin) Messages.DELETED_BY_ADMIN else Messages.MESSAGE_DELETED
    else -> m.text.orEmpty()
}

/** Where the phone's chat downloads go, as the user knows it. */
private const val DOWNLOADS = "Downloads"
private const val SHARE_KEEP_MS = 60 * 60 * 1000L
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
    /** Attachments: the media cache, sends with files, Download and Share…; without it the bubbles show placeholders. */
    private val media: ChatMediaSupport? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
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

    private val _info = MutableStateFlow<String?>(null)
    /** The last finished action worth saying ("Saved to Downloads"); null once shown. */
    val info: StateFlow<String?> = _info.asStateFlow()
    fun dismissInfo() { _info.value = null }

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

    /** The open chat's sends with files that are not posted yet (greyed, with progress), or failed until dismissed. */
    val pending: StateFlow<List<PendingSend>> = combine(selectedId, controller.pendingSends) { id, list -> list.filter { it.chatId == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Where the bubbles get thumbnails and files; null without [media]. */
    val mediaSource: ChatMediaSource? = media?.let { m ->
        object : ChatMediaSource {
            override suspend fun thumbnail(att: Attachment): File? = m.cache.thumbnail(att, api)
            override suspend fun file(att: Attachment): File = m.cache.get(att, api)
            override fun pin(fileId: String) = m.cache.pin(fileId)
            override fun unpin(fileId: String) = m.cache.unpin(fileId)
        }
    }

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

    // ---- Attachments ----

    /** Sends the picked files ([uris], `content://` strings) to the open chat at once; they upload in the background. */
    fun attach(uris: List<String>) {
        val chatId = selectedId.value ?: return
        if (uris.isNotEmpty()) sendFiles(chatId, null, uris)
    }

    /** Copies [uris] into the app (a picked file's grant is temporary) and queues the send with [text]. */
    private fun sendFiles(chatId: String, text: String?, uris: List<String>) {
        val m = media ?: return
        viewModelScope.launch {
            val dir = m.sendFiles.newDir()
            var queued = false
            try {
                val imported = withContext(io) {
                    try {
                        m.importer.import(uris, dir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) { // e.g. a SecurityException when the grant was revoked
                        ChatImport(emptyList(), listOf(Messages.unreadable("what you shared")))
                    }
                }
                imported.notices.firstOrNull()?.let { _notice.value = it }
                if (imported.files.isEmpty() && text.isNullOrBlank()) return@launch
                queued = call { controller.send(chatId, text, imported.files, m.thumbnailer) } != null && imported.files.isNotEmpty()
            } finally {
                m.sendFiles.done(dir, queued)
            }
        }
    }

    fun dismissSend(id: String) = controller.dismissSend(id)

    /**
     * Saves [atts] of [m] to `Download/Mjolnir` through the publisher, each from the media cache (downloaded into it first);
     * a sent folder keeps its folders under a free top-folder name. Says `Saved to Downloads`, or why not.
     */
    fun download(m: ChatMessage, atts: List<Attachment> = m.attachments) {
        val s = media ?: return
        if (atts.isEmpty()) return
        viewModelScope.launch {
            val stage = File(s.exportDir, "dl-${UUID.randomUUID()}")
            try {
                val paths = atts.map { safeRelativePath(it.relativePath ?: it.name) }
                val folders = mutableMapOf<String, String>()
                withContext(io) {
                    stage.mkdirs()
                    for (top in paths.mapNotNull(::topFolder).distinct()) {
                        folders[top] = uniqueName(top) { c -> s.publisher.folderExists(c) || folders.values.any { it.equals(c, ignoreCase = true) } }
                    }
                }
                atts.forEachIndexed { i, att ->
                    val part = File(stage, i.toString())
                    controller.download(m.chatId, att, part)
                    val segments = paths[i].split('/')
                    val folder = if (segments.size < 2) null
                    else (listOf(folders.getValue(segments.first())) + segments.subList(1, segments.size - 1)).joinToString("/")
                    withContext(io) {
                        s.publisher.publish(part, ReceivedPath(folder, segments.last()), FileRef(att.fileId, segments.last(), att.sizeBytes, att.mime, "", paths[i]))
                        part.delete()
                    }
                }
                _info.value = Messages.savedTo(DOWNLOADS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                _notice.value = e.userMessage ?: Messages.chatError(e)
            } catch (e: Exception) { // the disk or MediaStore refused
                _notice.value = "Couldn't save ${atts.first().name}"
            } finally {
                withContext(NonCancellable + io) { stage.deleteRecursively() }
            }
        }
    }

    private val _shareRequest = MutableStateFlow<ShareRequest?>(null)
    /** Files of a message ready to hand to the system's share sheet; the screen calls [shareHandled] once it did. */
    val shareRequest: StateFlow<ShareRequest?> = _shareRequest.asStateFlow()
    fun shareHandled() { _shareRequest.value = null }

    /** Share… on a message with attachments: copies them under their own names into the export folder, then [shareRequest]. */
    fun share(m: ChatMessage) {
        val s = media ?: return
        viewModelScope.launch {
            val dir = File(s.exportDir, "share-${UUID.randomUUID()}")
            try {
                withContext(io) {
                    // Another app may still be reading an earlier share; an hour is plenty.
                    s.exportDir.listFiles().orEmpty()
                        .filter { it.name.startsWith("share-") && it.lastModified() < System.currentTimeMillis() - SHARE_KEEP_MS }
                        .forEach { it.deleteRecursively() }
                    dir.mkdirs()
                }
                val files = m.attachments.map { att ->
                    val target = File(dir, uniqueName(safeFileName(att.name)) { File(dir, it).exists() })
                    controller.download(m.chatId, att, target)
                    target to att.mime
                }
                _shareRequest.value = ShareRequest(files, m.text?.takeIf { it.isNotBlank() })
            } catch (e: CancellationException) {
                withContext(NonCancellable + io) { dir.deleteRecursively() }
                throw e
            } catch (e: RelayException) {
                withContext(io) { dir.deleteRecursively() }
                _notice.value = e.userMessage ?: Messages.chatError(e)
            } catch (e: Exception) {
                withContext(io) { dir.deleteRecursively() }
                _notice.value = "Couldn't share ${m.attachments.firstOrNull()?.name ?: "that"}"
            }
        }
    }

    // ---- Sharing into a chat ----

    private val _shareOffer = MutableStateFlow<ShareOffer?>(null)
    /** Something another app shared, while the "Send to a chat" picker is up. */
    val shareOffer: StateFlow<ShareOffer?> = _shareOffer.asStateFlow()

    /** Another app shared [content]: offers the chats this phone can write in, or goes straight to Throw when there are none. */
    fun offerShare(content: SharedContent) {
        viewModelScope.launch {
            fun writable() = controller.chats.value.filter { it.member }
            if (writable().isEmpty()) quietly { controller.refresh() } // e.g. a cold start from the share sheet
            val chats = writable().sortedWith(compareBy<ChatSummary>({ it.spaceName.lowercase() }, { it.name.lowercase() }))
            if (chats.isEmpty() || media == null) content.throwInstead() else _shareOffer.value = ShareOffer(content, chats)
        }
    }

    /** Sends what was shared to [chatId] and opens it there. */
    fun sendShare(chatId: String) {
        val content = _shareOffer.value?.content ?: return
        _shareOffer.value = null
        open(chatId)
        val text = content.text?.trim()?.takeIf { it.isNotEmpty() }?.let { capCodePoints(it, Limits.MAX_CHAT_TEXT_LENGTH) }
        if (content.uris.isNotEmpty()) sendFiles(chatId, text, content.uris)
        else if (text != null) run { controller.send(chatId, text) }
    }

    /** The picker's Throw: the share goes to the Throw tray as it did before chats. */
    fun throwShare() {
        val content = _shareOffer.value?.content ?: return
        _shareOffer.value = null
        content.throwInstead()
    }

    fun cancelShare() { _shareOffer.value = null }

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
