package app.mjolnir.client

import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.ChatKind
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Receipt
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.topFolder

/** [text] cut to at most [max] code points (never inside a surrogate pair). */
fun capCodePoints(text: String, max: Int): String =
    if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

/** The space's members who could be invited to a chat of [chatMembers]: everyone else but this device. */
fun inviteCandidates(space: Space, chatMembers: List<Peer>, selfId: String?): List<Peer> =
    space.members.filter { m -> m.deviceId != selfId && chatMembers.none { it.deviceId == m.deviceId } }

/** Members of the chat you can @-mention for [query]: everyone but yourself whose shown name contains it, any case. */
fun mentionCandidates(members: List<Peer>, selfId: String?, query: String, shownName: (Peer) -> String = { it.name }): List<Peer> =
    members.filter { it.deviceId != selfId && shownName(it).contains(query.trim(), ignoreCase = true) }

/** The text typed after the last `@` of [text] while the picker should be open: the `@` starts a word and nothing was typed past the name. */
fun mentionQuery(text: String): String? {
    val at = text.lastIndexOf('@')
    if (at < 0 || (at > 0 && !text[at - 1].isWhitespace())) return null
    val query = text.substring(at + 1)
    return query.takeIf { q -> q.none { it.isWhitespace() } }
}

/** [text] with the `@query` being typed replaced by `@{shown} `. */
fun insertMention(text: String, shown: String): String = text.substring(0, text.lastIndexOf('@')) + "@$shown "

/**
 * What goes on the wire for a [draft] with picked mentions (member and the shown name inserted for it): the text with each
 * picked `@{shown}` rewritten to the member's own name, so every device can show it by its own nickname, and the device ids
 * in order of appearance. Only picks count: a name typed without picking stays plain text. Whole names only: "@Ann" is not
 * inside "@Annabel".
 */
fun outgoingMentions(draft: String, picked: List<Pair<Peer, String>>): Pair<String, List<String>> {
    val out = StringBuilder()
    val ids = linkedSetOf<String>()
    var i = 0
    while (i < draft.length) {
        val hit = if (draft[i] == '@') picked.filter { isMentionAt(draft, i, it.second) }.maxByOrNull { it.second.length } else null
        if (hit == null) { out.append(draft[i]); i++; continue }
        out.append('@').append(hit.first.name)
        ids += hit.first.deviceId
        i += 1 + hit.second.length
    }
    return out.toString() to ids.toList()
}

/** The members picked from the @ picker for a message being written: the only way a mention is recorded. Both apps track picks with it. */
data class MentionPicks(val ids: List<String> = emptyList()) {
    fun with(deviceId: String) = if (deviceId in ids) this else MentionPicks(ids + deviceId)

    /** The wire text and mentions of [draft] (see [outgoingMentions]); never yourself, never a member who left. */
    fun outgoing(draft: String, members: List<Peer>, selfId: String?, shownName: (Peer) -> String = { it.name }): Pair<String, List<String>> =
        outgoingMentions(draft, ids.mapNotNull { id -> members.firstOrNull { it.deviceId == id && it.deviceId != selfId } }.map { it to shownName(it) })

    companion object {
        /** Picks for editing [message]: its mentions are already picked. */
        fun of(message: ChatMessage) = MentionPicks(message.mentions)
    }
}

private fun isMentionAt(text: String, at: Int, name: String): Boolean {
    val end = at + 1 + name.length
    return text.startsWith(name, at + 1) && (end >= text.length || !text[end].isLetterOrDigit())
}

/** A message's text as shown on this device, with the character ranges of its mention chips. */
data class ShownText(val text: String, val spans: List<IntRange>)

/**
 * [text] with each mentioned member's `@{name}` shown as `@{shownName}`; [ShownText.spans] mark them for the amber chip style.
 * Mentions resolve by the recorded device ids, in order: of two members with the same name, the first `@name` is the first
 * mentioned id and the second the second, each shown by its own nickname.
 */
fun mentionSpans(text: String, mentions: List<String>, members: List<Peer>, shownName: (Peer) -> String = { it.name }): ShownText {
    val peers = mentions.distinct().mapNotNull { id -> members.firstOrNull { it.deviceId == id } }
    val used = mutableSetOf<String>()
    val spans = mutableListOf<IntRange>()
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val matching = if (text[i] == '@') peers.filter { isMentionAt(text, i, it.name) } else emptyList()
        val longest = matching.maxOfOrNull { it.name.length }
        val named = matching.filter { it.name.length == longest }
        val hit = named.firstOrNull { it.deviceId !in used } ?: named.firstOrNull()
        if (hit == null) { out.append(text[i]); i++; continue }
        used += hit.deviceId
        val shown = "@" + shownName(hit)
        spans += out.length until out.length + shown.length
        out.append(shown)
        i += 1 + hit.name.length
    }
    return ShownText(out.toString(), spans)
}

/** The newest message of [selfId] that is a real, undeleted message: the one the receipt line sits under. */
fun latestOwnMessage(messages: List<ChatMessage>, selfId: String?): ChatMessage? =
    messages.lastOrNull { it.author.deviceId == selfId && it.event == null && !it.deleted }

/** Who has read and who has (only) seen a message; [everyone] when every other member has read it. */
data class MessageReceipts(val read: List<Receipt>, val seen: List<Receipt>, val everyone: Boolean)

fun messageReceipts(message: ChatMessage, receipts: List<Receipt>, members: List<Peer>): MessageReceipts {
    val others = members.map { it.deviceId }.filter { it != message.author.deviceId }
    val byId = receipts.associateBy { it.deviceId }
    val mine = others.mapNotNull { byId[it] }
    val at = message.createdRev ?: message.rev // an edit moves the rev, not what has been read
    val read = mine.filter { it.readRev >= at }
    val seen = mine.filter { it.readRev < at && it.seenRev >= at }
    return MessageReceipts(read, seen, others.isNotEmpty() && read.size == others.size)
}

/** A run of a message as drawn: plain, a link to open, or an @mention chip. */
data class Segment(val text: String, val link: String? = null, val mention: Boolean = false)

/** [text] as the runs to draw on this device: mentions shown by nickname (see [mentionSpans]) and links split out. */
fun messageSegments(text: String, mentions: List<String>, members: List<Peer>, shownName: (Peer) -> String = { it.name }): List<Segment> {
    val shown = mentionSpans(text, mentions, members, shownName)
    val out = mutableListOf<Segment>()
    var at = 0
    for (part in linkify(shown.text)) {
        val end = at + (if (part is TextPart.Link) part.url.length else (part as TextPart.Plain).text.length)
        var pos = at
        for (span in shown.spans.filter { it.first < end && it.last >= at }) {
            if (span.first > pos) out += Segment(shown.text.substring(pos, span.first), (part as? TextPart.Link)?.url)
            val from = maxOf(span.first, pos); val to = minOf(span.last + 1, end)
            out += Segment(shown.text.substring(from, to), (part as? TextPart.Link)?.url, mention = true)
            pos = to
        }
        if (pos < end) out += Segment(shown.text.substring(pos, end), (part as? TextPart.Link)?.url)
        at = end
    }
    return out
}

/** The receipt line under [message] (see [Messages.receiptLine]); [shownName] gives each member's nickname. */
fun receiptLineFor(message: ChatMessage, receipts: List<Receipt>, members: List<Peer>, shownName: (Peer) -> String = { it.name }): String {
    val r = messageReceipts(message, receipts, members)
    fun names(l: List<Receipt>) = l.mapNotNull { rc -> members.firstOrNull { it.deviceId == rc.deviceId }?.let(shownName) }
    return Messages.receiptLine(names(r.read), names(r.seen), r.everyone)
}

/** How a message's attachments are shown: one bubble per single file, one per top folder of a sent folder. */
sealed interface AttachmentGroup<out T> {
    data class Single<T>(val item: T) : AttachmentGroup<T>
    /** The files of folder [name], in sent order; shown as [Messages.folderDetail] of [count] and [totalBytes]. */
    data class Folder<T>(val name: String, val items: List<T>, val totalBytes: Long) : AttachmentGroup<T> {
        val count: Int get() = items.size
    }
}

/** [attachments] grouped for display, in order of first appearance: by [topFolder] of their relativePath, singles alone. */
fun groupAttachments(attachments: List<Attachment>): List<AttachmentGroup<Attachment>> =
    groupByFolder(attachments, { it.relativePath }, { it.sizeBytes })

/** A pending send's files grouped like [groupAttachments], for its greyed bubbles. */
fun groupPendingFiles(files: List<PendingFile>): List<AttachmentGroup<PendingFile>> =
    groupByFolder(files, { it.relativePath }, { it.sizeBytes })

private fun <T> groupByFolder(items: List<T>, path: (T) -> String?, size: (T) -> Long): List<AttachmentGroup<T>> {
    val byFolder = items.groupBy { path(it)?.let(::topFolder) }
    val shown = HashSet<String>()
    return items.mapNotNull { item ->
        when (val top = path(item)?.let(::topFolder)) {
            null -> AttachmentGroup.Single(item)
            else -> if (shown.add(top)) byFolder.getValue(top).let { AttachmentGroup.Folder(top, it, it.sumOf(size)) } else null
        }
    }
}

/** Open channels hold the whole space: nobody is invited, leaves or hands over ownership; private chats work as before. */
fun canInvite(chat: ChatSummary): Boolean = chat.kind == ChatKind.PRIVATE && chat.member
fun canLeave(chat: ChatSummary): Boolean = chat.kind == ChatKind.PRIVATE && chat.member
fun canTransferOwner(chat: ChatSummary, selfId: String?): Boolean = chat.kind == ChatKind.PRIVATE && chat.ownerId == selfId

/** Rename and delete: the chat's owner (a channel's creator) or the space admin. */
fun canManage(chat: ChatSummary, selfId: String?, isSpaceAdmin: Boolean): Boolean = isSpaceAdmin || (selfId != null && chat.ownerId == selfId)

/** The chat list in two sections: open channels by name (any case), then private chats in their given order. */
data class ChatSections(val channels: List<ChatSummary>, val privateChats: List<ChatSummary>)

fun chatSections(chats: List<ChatSummary>): ChatSections {
    val (channels, privateChats) = chats.partition { it.kind == ChatKind.OPEN }
    return ChatSections(channels.sortedBy { it.name.lowercase() }, privateChats)
}
