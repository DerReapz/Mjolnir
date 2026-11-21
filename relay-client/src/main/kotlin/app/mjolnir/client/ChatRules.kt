package app.mjolnir.client

import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Receipt
import app.mjolnir.protocol.Space

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
 * `@{shown}` rewritten to the member's own name, so every device can show it by its own nickname, and the device ids of
 * the picks still in the text.
 */
fun outgoingMentions(draft: String, picked: List<Pair<Peer, String>>): Pair<String, List<String>> {
    // Longest names first, and only whole names: "@Ann" is not inside "@Annabel".
    val ids = linkedSetOf<String>()
    var text = draft
    val ordered = picked.sortedByDescending { it.second.length }
    val marks = ordered.mapIndexed { i, (peer, shown) ->
        val token = Regex("@" + Regex.escape(shown) + "(?![\\p{L}\\p{N}])")
        if (token.containsMatchIn(text)) {
            ids += peer.deviceId
            text = token.replace(text) { "@\u0000$i\u0000" } // park it so a later name cannot match inside the replacement
        }
        "@\u0000$i\u0000" to "@${peer.name}"
    }
    for ((mark, name) in marks) text = text.replace(mark, name)
    return text to picked.map { it.first.deviceId }.distinct().filter { it in ids }
}

private fun isMentionAt(text: String, at: Int, name: String): Boolean {
    val end = at + 1 + name.length
    return text.startsWith(name, at + 1) && (end >= text.length || !text[end].isLetterOrDigit())
}

/** A message's text as shown on this device, with the character ranges of its mention chips. */
data class ShownText(val text: String, val spans: List<IntRange>)

/** [text] with each mentioned member's `@{name}` shown as `@{shownName}`; [ShownText.spans] mark them for the amber chip style. */
fun mentionSpans(text: String, mentions: List<String>, members: List<Peer>, shownName: (Peer) -> String = { it.name }): ShownText {
    val peers = mentions.mapNotNull { id -> members.firstOrNull { it.deviceId == id } }.distinct()
    val spans = mutableListOf<IntRange>()
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val hit = if (text[i] == '@') peers.filter { isMentionAt(text, i, it.name) }.maxByOrNull { it.name.length } else null
        if (hit == null) { out.append(text[i]); i++; continue }
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
    val read = mine.filter { it.readRev >= message.rev }
    val seen = mine.filter { it.readRev < message.rev && it.seenRev >= message.rev }
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
