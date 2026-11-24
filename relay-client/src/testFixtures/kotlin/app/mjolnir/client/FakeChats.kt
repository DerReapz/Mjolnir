package app.mjolnir.client

import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatKind
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.ChatSync
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.InviteState
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.MessageReceipt
import app.mjolnir.protocol.Receipt
import app.mjolnir.protocol.RelayEvent

/**
 * The in-memory chats of a [FakeRelayApi], with the relay's rules and error codes (see ChatRoutes.kt) for the device
 * the fake plays ([FakeRelayApi.me]). Other people act through the `incoming*` / `invitedBy` helpers.
 */
class FakeChats internal constructor(private val api: FakeRelayApi) {
    class Chat(
        val id: String,
        val spaceId: String,
        var name: String,
        var ownerId: String,
        /** In joining order: the longest-standing member first. */
        val members: MutableList<Peer>,
        val messages: MutableList<ChatMessage> = mutableListOf(),
        var rev: Long = 0,
        val readRevs: MutableMap<String, Long> = mutableMapOf(),
        val seenRevs: MutableMap<String, Long> = mutableMapOf(),
        val seenAt: MutableMap<String, Long> = mutableMapOf(),
        val readAt: MutableMap<String, Long> = mutableMapOf(),
        val kind: ChatKind = ChatKind.PRIVATE,
        /** Devices that muted the chat. */
        val mutedBy: MutableSet<String> = mutableSetOf(),
    )

    class Invite(val id: String, val chatId: String, val inviteeId: String, val inviterId: String, var state: InviteState)

    val chats = LinkedHashMap<String, Chat>()
    val invites = mutableListOf<Invite>()
    /** While set, posting, editing and deleting messages answer 429. */
    var rateLimited = false
    /** While set, reporting seen fails with a network error. */
    var failSeen = false
    /** `(chatId, sinceRev)` of every [sync], in order. */
    val syncCalls = mutableListOf<Pair<String, Long>>()
    /** The 1-based number, among all [sync] calls so far, of the call that fails with a network error (once). */
    var failSyncOnCall: Int? = null
    /** While set, a sync answers only once this completes (the answer is read before the wait, like a slow network). */
    var syncGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var postCalls = 0
        private set
    /** `(chatId, rev)` of every [markSeen] and [markRead] call that reached the relay, in order. */
    val seenCalls = mutableListOf<Pair<String, Long>>()
    val readCalls = mutableListOf<Pair<String, Long>>()

    private var clock = 1_000L
    private var nextChat = 0
    private var nextMessage = 0
    private var nextInvite = 0

    private fun tick(): Long { clock += 1_000; return clock }
    private fun err(kind: RelayErrorKind, code: String) = RelayException(kind, code)
    private val notFound get() = err(RelayErrorKind.NOT_FOUND, "not_found")
    private val meId get() = api.me.deviceId

    private fun peer(id: String): Peer =
        if (id == meId) api.me
        else api.spaceList.flatMap { it.members }.firstOrNull { it.deviceId == id } ?: Peer(id, "Unknown device", DeviceKind.PC)

    private fun inSpace(spaceId: String, id: String) = api.spaceList.any { s -> s.id == spaceId && s.members.any { it.deviceId == id } }
    private fun adminOf(chat: Chat) = api.spaceList.firstOrNull { it.id == chat.spaceId }?.adminId
    private fun isMember(chat: Chat, id: String) = chat.members.any { it.deviceId == id }

    private class Access(val chat: Chat, val isAdmin: Boolean, val isMember: Boolean)

    /** 404 unless the viewer is in the chat or administers its space. */
    private fun access(chatId: String): Access {
        val chat = chats[chatId] ?: throw notFound
        val admin = adminOf(chat) == meId
        val member = isMember(chat, meId)
        if (!admin && !member) throw notFound
        return Access(chat, admin, member)
    }

    private fun validName(raw: String): String =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= Limits.MAX_SPACE_NAME_LENGTH && it.none(Char::isISOControl) }
            ?: throw err(RelayErrorKind.INVALID, "invalid_name")

    private fun summary(chat: Chat) = ChatSummary(
        chat.id, chat.spaceId, api.spaceList.firstOrNull { it.id == chat.spaceId }?.name.orEmpty(), chat.name, chat.ownerId, chat.rev,
        chat.readRevs[meId] ?: 0, isMember(chat, meId), chat.messages.filter { !it.deleted }.maxByOrNull { it.createdRev ?: it.rev },
        chat.kind, meId in chat.mutedBy,
    )

    private fun openRefusal() = err(RelayErrorKind.ALREADY_SENT, "not_for_open_channels")

    private fun changed(chat: Chat) { api.events.tryEmit(RelayEvent.ChatChanged(chat.id, chat.rev)) }

    private fun mentionsOf(chat: Chat, ids: List<String>): List<String> =
        ids.distinct().also { l -> if (l.any { !isMember(chat, it) }) throw err(RelayErrorKind.INVALID, "invalid_mention") }

    private fun receiptsOf(chat: Chat) = chat.members.map {
        val id = it.deviceId
        Receipt(id, maxOf(chat.seenRevs[id] ?: 0, chat.readRevs[id] ?: 0), chat.readRevs[id] ?: 0, chat.seenAt[id], chat.readAt[id])
    }

    private fun record(chat: Chat, actor: String, event: ChatEvent) {
        chat.messages += ChatMessage("e-${++nextMessage}", chat.id, peer(actor), ++chat.rev, tick(), event = event)
    }

    private fun invite(chat: Chat, row: Invite) = ChatInvite(
        row.id, chat.id, chat.name, api.spaceList.firstOrNull { it.id == chat.spaceId }?.name.orEmpty(), peer(row.inviteeId), peer(row.inviterId), row.state,
    )

    // ---- Test setup: the world outside the viewer ----

    /** A chat in [spaceId] with [members] (default: just [owner]), at rev 0. */
    fun add(spaceId: String, name: String, owner: Peer, members: List<Peer>): String {
        val chat = Chat("chat-${++nextChat}", spaceId, name, owner.deviceId, (listOf(owner) + members).distinctBy { it.deviceId }.toMutableList())
        chats[chat.id] = chat
        return chat.id
    }

    /** An open channel of [spaceId]: every member of the space is in it, [owner] created it. */
    fun addChannel(spaceId: String, name: String, owner: Peer): String {
        val members = api.spaceList.first { it.id == spaceId }.members
        val chat = Chat("chat-${++nextChat}", spaceId, name, owner.deviceId, (listOf(owner) + members).distinctBy { it.deviceId }.toMutableList(), kind = ChatKind.OPEN)
        chats[chat.id] = chat
        return chat.id
    }

    /** [author] posts [text]; the viewer's relay connection is told. */
    fun incoming(chatId: String, author: Peer, text: String?, mentions: List<String> = emptyList(), attachments: List<Attachment> = emptyList()): ChatMessage {
        val chat = chats.getValue(chatId)
        val m = ChatMessage("m-${++nextMessage}", chatId, author, ++chat.rev, tick(), text = text, mentions = mentions, createdRev = chat.rev, attachments = attachments)
        chat.messages += m
        changed(chat)
        return m
    }

    /** The author of [msgId] edits it. */
    fun incomingEdit(chatId: String, msgId: String, text: String, mentions: List<String>? = null) {
        val chat = chats.getValue(chatId)
        val i = chat.messages.indexOfFirst { it.id == msgId }
        chat.messages[i] = chat.messages[i].copy(rev = ++chat.rev, editedAtEpochMs = tick(), text = text, mentions = mentions ?: chat.messages[i].mentions)
        changed(chat)
    }

    /** The author of [msgId] deletes it. */
    fun incomingDelete(chatId: String, msgId: String) {
        val chat = chats.getValue(chatId)
        val i = chat.messages.indexOfFirst { it.id == msgId }
        freeFiles(chat.messages[i])
        chat.messages[i] = chat.messages[i].copy(rev = ++chat.rev, deleted = true, text = null, attachments = emptyList())
        changed(chat)
    }

    /** The chat vanishes (deleted by someone else), without any event. */
    fun drop(chatId: String) {
        chats.remove(chatId)?.messages?.forEach(::freeFiles); invites.removeAll { it.chatId == chatId }
    }

    /** As the relay does on every deletion: the message's files and thumbnails are gone at once (downloads get 404). */
    private fun freeFiles(m: ChatMessage) {
        for (a in m.attachments) { api.blobs.remove(a.fileId); a.thumbFileId?.let(api.blobs::remove) }
    }

    /** [by], a member of the chat, invites [inviteeId] (approved at once when [by] is the owner). */
    fun invitedBy(by: Peer, chatId: String, inviteeId: String): ChatInvite {
        val chat = chats.getValue(chatId)
        return createInvite(chat, by.deviceId, inviteeId)
    }

    fun readRev(chatId: String): Long = chats.getValue(chatId).readRevs[meId] ?: 0

    private class Mark(val deviceId: String, val kind: String, val rev: Long, val at: Long)
    private val marks = LinkedHashMap<String, MutableList<Mark>>()

    private fun receiptFor(chat: Chat, id: String) =
        Receipt(id, maxOf(chat.seenRevs[id] ?: 0, chat.readRevs[id] ?: 0), chat.readRevs[id] ?: 0, chat.seenAt[id], chat.readAt[id])

    /** [id] moves its seen (or, with [read], read and seen) marker to [rev] (never above the chat's); the new receipt, or null when nothing moved. */
    private fun report(chat: Chat, id: String, read: Boolean, rev: Long): Receipt? {
        val to = minOf(rev, chat.rev)
        val at = tick()
        val list = marks.getOrPut(chat.id) { mutableListOf() }
        var moved = false
        if (read && to > (chat.readRevs[id] ?: 0)) {
            chat.readRevs[id] = to; chat.readAt[id] = at; list += Mark(id, "read", to, at); moved = true
        }
        if (to > (chat.seenRevs[id] ?: 0)) { chat.seenRevs[id] = to; chat.seenAt[id] = at; list += Mark(id, "seen", to, at); moved = true }
        return if (moved) receiptFor(chat, id) else null
    }

    private fun emitReceipt(chat: Chat, r: Receipt?) {
        if (r != null) api.events.tryEmit(RelayEvent.ChatChanged(chat.id, chat.rev, receipts = listOf(r)))
    }

    /** [by] reports having seen the chat up to [rev]; the viewer's relay connection is told (the rev stays). */
    fun incomingSeen(chatId: String, by: Peer, rev: Long): Receipt? {
        val chat = chats.getValue(chatId)
        return report(chat, by.deviceId, read = false, rev = rev).also { emitReceipt(chat, it) }
    }

    /** [by] reports having read the chat up to [rev] (which counts as seen). */
    fun incomingRead(chatId: String, by: Peer, rev: Long): Receipt? {
        val chat = chats.getValue(chatId)
        return report(chat, by.deviceId, read = true, rev = rev).also { emitReceipt(chat, it) }
    }

    /** When each member first saw and read [msgId]: the first mark at or above its rev. */
    fun messageReceipts(chatId: String, msgId: String): List<MessageReceipt> {
        val a = access(chatId)
        val msg = a.chat.messages.firstOrNull { it.id == msgId && it.event == null } ?: throw notFound
        fun first(id: String, kind: String) = marks[chatId].orEmpty().filter { it.deviceId == id && it.kind == kind && it.rev >= (msg.createdRev ?: msg.rev) }.minOfOrNull { it.at }
        return a.chat.members.map { MessageReceipt(it.deviceId, first(it.deviceId, "seen"), first(it.deviceId, "read")) }
    }

    fun seenRev(chatId: String): Long = chats.getValue(chatId).seenRevs[meId] ?: 0

    /** [deviceId] left [spaceId]: it leaves the space's chats and its invites go (a chat left empty is deleted). */
    internal fun onLeftSpace(spaceId: String, deviceId: String) {
        chats.values.filter { it.spaceId == spaceId }.toList().forEach { chat ->
            invites.removeAll { it.chatId == chat.id && (it.inviteeId == deviceId || it.inviterId == deviceId) }
            if (isMember(chat, deviceId)) removeInternal(chat, deviceId, actor = deviceId, removed = false, forced = true)
        }
    }

    internal fun onSpaceDeleted(spaceId: String) {
        chats.values.filter { it.spaceId == spaceId }.map { it.id }.forEach(::drop)
    }

    // ---- The relay API ----

    fun list(spaceId: String): List<ChatSummary> {
        val space = api.spaceList.firstOrNull { it.id == spaceId && inSpace(spaceId, meId) } ?: throw notFound
        return chats.values.filter { it.spaceId == space.id && (space.adminId == meId || isMember(it, meId)) }.map(::summary)
    }

    fun create(spaceId: String, name: String): ChatSummary {
        if (!inSpace(spaceId, meId)) throw notFound
        val chat = Chat("chat-${++nextChat}", spaceId, validName(name), meId, mutableListOf(api.me))
        chats[chat.id] = chat
        return summary(chat)
    }

    fun createChannel(spaceId: String, name: String): ChatSummary {
        if (!inSpace(spaceId, meId)) throw notFound
        return summary(chats.getValue(addChannel(spaceId, validName(name), api.me)))
    }

    fun setMuted(chatId: String, muted: Boolean): ChatSummary {
        val a = access(chatId)
        if (!a.isMember) throw err(RelayErrorKind.NOT_PAIRED, "not_member")
        if (muted) a.chat.mutedBy += meId else a.chat.mutedBy -= meId
        return summary(a.chat)
    }

    fun rename(id: String, name: String): ChatSummary {
        val a = access(id)
        if (a.chat.ownerId != meId && !a.isAdmin) throw err(RelayErrorKind.NOT_PAIRED, "not_owner")
        a.chat.name = validName(name)
        record(a.chat, meId, ChatEvent.Renamed(a.chat.name)); changed(a.chat)
        return summary(a.chat)
    }

    fun delete(id: String) {
        val a = access(id)
        if (a.chat.ownerId != meId && !a.isAdmin) throw err(RelayErrorKind.NOT_PAIRED, "not_owner")
        drop(id)
    }

    private fun createInvite(chat: Chat, inviterId: String, inviteeId: String): ChatInvite {
        if (!inSpace(chat.spaceId, inviteeId)) throw err(RelayErrorKind.INVALID, "not_in_space")
        if (isMember(chat, inviteeId)) throw err(RelayErrorKind.ALREADY_SENT, "already_member")
        if (invites.any { it.chatId == chat.id && it.inviteeId == inviteeId && it.state in LIVE }) throw err(RelayErrorKind.ALREADY_SENT, "already_invited")
        invites.removeAll { it.chatId == chat.id && it.inviteeId == inviteeId }
        val state = if (chat.ownerId == inviterId) InviteState.APPROVED else InviteState.PENDING_APPROVAL
        return Invite("inv-${++nextInvite}", chat.id, inviteeId, inviterId, state).also { invites += it }.let { invite(chat, it) }
    }

    fun inviteTo(chatId: String, deviceId: String): ChatInvite {
        val a = access(chatId)
        if (a.chat.kind == ChatKind.OPEN) throw openRefusal()
        if (!a.isMember) throw err(RelayErrorKind.NOT_PAIRED, "not_member")
        return createInvite(a.chat, meId, deviceId)
    }

    /** Invites the viewer can act on: approvals owed as owner, own approved invitations; none that went stale. */
    fun invitesForMe(): List<ChatInvite> = invites.filter { row ->
        val chat = chats[row.chatId] ?: return@filter false
        inSpace(chat.spaceId, row.inviteeId) && inSpace(chat.spaceId, row.inviterId) && !isMember(chat, row.inviteeId) &&
            ((row.state == InviteState.PENDING_APPROVAL && chat.ownerId == meId) || (row.state == InviteState.APPROVED && row.inviteeId == meId))
    }.map { invite(chats.getValue(it.chatId), it) }

    private fun inviteAccess(id: String): Pair<Invite, Chat> {
        val row = invites.firstOrNull { it.id == id } ?: throw notFound
        val chat = chats[row.chatId] ?: throw notFound
        if (row.inviteeId != meId && !isMember(chat, meId) && adminOf(chat) != meId) throw notFound
        return row to chat
    }

    private fun decide(id: String, to: InviteState) {
        val (row, chat) = inviteAccess(id)
        if (chat.ownerId != meId) throw err(RelayErrorKind.NOT_PAIRED, "not_owner")
        if (row.state != InviteState.PENDING_APPROVAL) throw err(RelayErrorKind.ALREADY_SENT, "invite_unavailable")
        row.state = to
    }

    fun approve(id: String) = decide(id, InviteState.APPROVED)
    fun deny(id: String) = decide(id, InviteState.DENIED)

    fun decline(id: String) {
        val (row, _) = inviteAccess(id)
        if (row.inviteeId != meId) throw err(RelayErrorKind.NOT_PAIRED, "not_invitee")
        if (row.state != InviteState.APPROVED) throw err(RelayErrorKind.ALREADY_SENT, "invite_unavailable")
        row.state = InviteState.DECLINED
    }

    fun join(id: String): ChatSummary {
        val (row, chat) = inviteAccess(id)
        if (row.inviteeId != meId) throw err(RelayErrorKind.NOT_PAIRED, "not_invitee")
        if (row.state != InviteState.APPROVED || !inSpace(chat.spaceId, meId)) throw err(RelayErrorKind.ALREADY_SENT, "invite_unavailable")
        chat.members += api.me
        record(chat, meId, ChatEvent.MemberJoined(meId)); changed(chat)
        invites.remove(row)
        return summary(chat)
    }

    fun transferOwner(chatId: String, deviceId: String): ChatSummary {
        val a = access(chatId)
        if (a.chat.kind == ChatKind.OPEN) throw openRefusal()
        if (a.chat.ownerId != meId) throw err(RelayErrorKind.NOT_PAIRED, "not_owner")
        if (deviceId == meId) throw err(RelayErrorKind.INVALID, "already_owner")
        if (!isMember(a.chat, deviceId)) throw err(RelayErrorKind.INVALID, "not_member")
        a.chat.ownerId = deviceId
        record(a.chat, meId, ChatEvent.OwnerChanged(deviceId)); changed(a.chat)
        return summary(a.chat)
    }

    /** Takes [deviceId] out; ownership goes to the longest-standing member; the last one out deletes the chat. */
    private fun removeInternal(chat: Chat, deviceId: String, actor: String, removed: Boolean, forced: Boolean) {
        if (chat.ownerId == deviceId && !removed && !forced) throw err(RelayErrorKind.ALREADY_SENT, "owner_must_transfer")
        chat.members.removeAll { it.deviceId == deviceId }
        invites.removeAll { it.chatId == chat.id && (it.inviteeId == deviceId || (removed && it.inviterId == deviceId)) }
        if (chat.members.isEmpty()) { drop(chat.id); return }
        record(chat, actor, if (removed) ChatEvent.MemberRemoved(deviceId, byAdmin = true) else ChatEvent.MemberLeft(deviceId))
        if (chat.ownerId == deviceId) {
            chat.ownerId = chat.members.first().deviceId
            record(chat, actor, ChatEvent.OwnerChanged(chat.ownerId))
        }
        changed(chat)
    }

    fun removeMember(chatId: String, deviceId: String) {
        val a = access(chatId)
        if (a.chat.kind == ChatKind.OPEN) throw openRefusal()
        val self = deviceId == meId
        if (!self && !a.isAdmin) throw err(RelayErrorKind.NOT_PAIRED, "not_admin")
        if (!isMember(a.chat, deviceId)) throw notFound
        removeInternal(a.chat, deviceId, actor = meId, removed = !self, forced = false)
    }

    fun adminJoin(chatId: String): ChatSummary {
        val a = access(chatId)
        if (a.chat.kind == ChatKind.OPEN) throw openRefusal()
        if (!a.isAdmin) throw err(RelayErrorKind.NOT_PAIRED, "not_admin")
        if (a.isMember) throw err(RelayErrorKind.ALREADY_SENT, "already_member")
        a.chat.members += api.me
        record(a.chat, meId, ChatEvent.MemberJoined(meId)); changed(a.chat)
        return summary(a.chat)
    }

    fun sync(chatId: String, sinceRev: Long): ChatSync {
        val a = access(chatId)
        syncCalls += chatId to sinceRev
        if (syncCalls.size == failSyncOnCall) throw err(RelayErrorKind.NETWORK, "down")
        val rows = a.chat.messages.filter { it.rev > sinceRev }.sortedBy { it.rev }
        return ChatSync(chatId, a.chat.rev, a.chat.ownerId, a.chat.members.toList(), rows.take(PAGE), rows.size > PAGE, receiptsOf(a.chat))
    }

    private fun text(raw: String): String {
        if (raw.isBlank()) throw err(RelayErrorKind.INVALID, "empty")
        if (raw.codePointCount(0, raw.length) > Limits.MAX_CHAT_TEXT_LENGTH) throw err(RelayErrorKind.INVALID, "too_long")
        return raw
    }

    private fun limited() { if (rateLimited) throw err(RelayErrorKind.RATE_LIMITED, "rate_limited") }

    /** Files (and thumbnails) already attached to a message: each upload is used once. */
    private val attachedFiles = mutableSetOf<String>()

    fun post(chatId: String, raw: String?, mentions: List<String> = emptyList(), attachments: List<Attachment> = emptyList()): ChatMessage {
        postCalls++
        limited()
        val a = access(chatId)
        if (!a.isMember) throw err(RelayErrorKind.NOT_PAIRED, "not_member")
        val body = if (attachments.isEmpty() || !raw.isNullOrBlank()) text(raw.orEmpty()) else null
        if (attachments.size > Limits.MAX_ATTACHMENTS_PER_MESSAGE) throw err(RelayErrorKind.INVALID, "too_many_attachments")
        val ids = mentionsOf(a.chat, mentions)
        val stored = attach(attachments)
        val m = ChatMessage("m-${++nextMessage}", chatId, api.me, ++a.chat.rev, tick(), text = body, mentions = ids, createdRev = a.chat.rev, attachments = stored)
        a.chat.messages += m
        changed(a.chat)
        return m
    }

    /** The relay's attach rules: the poster's own finished, unexpired uploads, each used once; size and mime come from the upload. */
    private fun attach(attachments: List<Attachment>): List<Attachment> {
        val fileIds = attachments.flatMap { listOfNotNull(it.fileId, it.thumbFileId) }
        val valid = fileIds.toSet().size == fileIds.size && fileIds.all { id -> id !in attachedFiles && id in api.blobs && api.uploads.any { it.id == id } }
        if (!valid) throw err(RelayErrorKind.INVALID, "invalid_files")
        attachedFiles += fileIds
        return attachments.map { at -> api.uploads.first { it.id == at.fileId }.let { ref -> at.copy(sizeBytes = ref.sizeBytes, mime = ref.mimeType) } }
    }

    private fun realMessage(chat: Chat, msgId: String): Int =
        chat.messages.indexOfFirst { it.id == msgId && it.event == null }.also { if (it < 0) throw notFound }

    fun edit(chatId: String, msgId: String, raw: String, mentions: List<String> = emptyList()): ChatMessage {
        limited()
        val a = access(chatId)
        val i = realMessage(a.chat, msgId)
        val m = a.chat.messages[i]
        if (m.author.deviceId != meId) throw err(RelayErrorKind.NOT_PAIRED, "not_author")
        val body = text(raw)
        if (m.deleted) throw err(RelayErrorKind.ALREADY_SENT, "deleted")
        val ids = mentionsOf(a.chat, mentions)
        return m.copy(rev = ++a.chat.rev, editedAtEpochMs = tick(), text = body, mentions = ids).also { a.chat.messages[i] = it; changed(a.chat) }
    }

    fun deleteMessage(chatId: String, msgId: String) {
        limited()
        val a = access(chatId)
        val i = realMessage(a.chat, msgId)
        val m = a.chat.messages[i]
        if (m.author.deviceId != meId && !a.isAdmin) throw err(RelayErrorKind.NOT_PAIRED, "not_author")
        if (m.deleted) throw err(RelayErrorKind.ALREADY_SENT, "deleted")
        freeFiles(m)
        a.chat.messages[i] = m.copy(rev = ++a.chat.rev, deleted = true, deletedByAdmin = a.isAdmin && m.author.deviceId != meId, text = null, attachments = emptyList())
        changed(a.chat)
    }

    fun markRead(chatId: String, rev: Long) {
        val a = access(chatId)
        if (!a.isMember) throw err(RelayErrorKind.NOT_PAIRED, "not_member")
        readCalls += chatId to rev
        report(a.chat, meId, read = true, rev = rev)
    }

    fun markSeen(chatId: String, rev: Long) {
        val a = access(chatId)
        if (!a.isMember) throw err(RelayErrorKind.NOT_PAIRED, "not_member")
        if (failSeen) throw err(RelayErrorKind.NETWORK, "down")
        seenCalls += chatId to rev
        report(a.chat, meId, read = false, rev = rev)
    }

    private companion object {
        const val PAGE = 200
        val LIVE = setOf(InviteState.PENDING_APPROVAL, InviteState.APPROVED)
    }
}
