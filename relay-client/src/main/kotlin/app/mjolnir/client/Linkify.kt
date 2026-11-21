package app.mjolnir.client

import app.mjolnir.protocol.urlSpans

/** A piece of message text: plain, or a link to open. */
sealed interface TextPart {
    data class Plain(val text: String) : TextPart
    data class Link(val url: String) : TextPart
}

/** Splits [text] into plain runs and links, with the same trimming of trailing punctuation as `extractUrls`. */
fun linkify(text: String): List<TextPart> {
    val parts = ArrayList<TextPart>()
    var at = 0
    for (span in urlSpans(text)) {
        if (span.range.first > at) parts += TextPart.Plain(text.substring(at, span.range.first))
        parts += TextPart.Link(span.url)
        at = span.range.last + 1
    }
    if (at < text.length) parts += TextPart.Plain(text.substring(at))
    return parts
}
