package app.mjolnir.protocol

import java.net.URI

fun isSafeTabUrl(url: String): Boolean {
    if (url.length > Limits.MAX_URL_LENGTH || url.any { it.isWhitespace() }) return false
    val uri = try { URI(url) } catch (_: Exception) { return false }
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    return !uri.host.isNullOrBlank()
}

private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private const val TRAILING_PUNCT = ".,;:!?'\""

fun extractUrls(text: String): List<String> {
    val out = LinkedHashSet<String>()
    for (m in URL_REGEX.findAll(text)) {
        var u = m.value
        while (true) {
            val last = u.lastOrNull() ?: break
            if (last in TRAILING_PUNCT) {
                u = u.dropLast(1)
            } else if (last == ')' && u.count { it == ')' } > u.count { it == '(' }) {
                u = u.dropLast(1)
            } else break
        }
        if (isSafeTabUrl(u)) out.add(u)
    }
    return out.toList()
}

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
