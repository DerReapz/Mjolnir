package app.mjolnir.protocol

import java.net.URI

fun isSafeTabUrl(url: String): Boolean {
    if (url.length > Limits.MAX_URL_LENGTH || url.any { it.isWhitespace() || it.isISOControl() }) return false
    val rest = when {
        url.startsWith("https://", ignoreCase = true) -> url.substring(8)
        url.startsWith("http://", ignoreCase = true) -> url.substring(7)
        else -> return false
    }
    var authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
    authority = authority.substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        val end = authority.indexOf(']')
        if (end < 0) return false
        authority.substring(0, end + 1)
    } else authority.substringBefore(':')
    return host.isNotEmpty() && host != "[]"
}

private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val OPENER = mapOf(')' to '(', ']' to '[', '}' to '{')
private const val TRAILING_PUNCT = ".,;:!?'\""

/** A safe URL found in a text: [range] is where it sits in the text (trailing punctuation excluded), [url] its value. */
data class UrlSpan(val range: IntRange, val url: String)

/** The safe http(s) URLs of [text] in order, with their positions; the trimming rules of [extractUrls]. */
fun urlSpans(text: String): List<UrlSpan> {
    val out = ArrayList<UrlSpan>()
    for (m in URL_REGEX.findAll(text)) {
        var u = m.value
        while (true) {
            val last = u.lastOrNull() ?: break
            if (last in TRAILING_PUNCT) {
                u = u.dropLast(1)
            } else if (last == '>') {
                u = u.dropLast(1)
            } else if (last in ")]}" && u.count { it == last } > u.count { it == OPENER.getValue(last) }) {
                u = u.dropLast(1)
            } else break
        }
        if (isSafeTabUrl(u)) out.add(UrlSpan(m.range.first until m.range.first + u.length, u))
    }
    return out
}

fun extractUrls(text: String): List<String> = urlSpans(text).mapTo(LinkedHashSet()) { it.url }.toList()

fun cleanTabs(tabs: List<Tab>): List<Tab> {
    val seen = HashSet<String>()
    val out = ArrayList<Tab>()
    for (t in tabs) {
        if (!isSafeTabUrl(t.url) || !seen.add(t.url)) continue
        val title = t.title?.trim()?.takeIf { it.isNotEmpty() }?.take(Limits.MAX_TITLE_LENGTH)
        out.add(Tab(t.url, title))
    }
    return out
}
