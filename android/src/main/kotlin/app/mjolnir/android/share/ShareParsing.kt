package app.mjolnir.android.share

import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.extractUrls

/** Web links found in shared [texts], in order and deduplicated. [subject] titles the tab only when there is exactly one. */
fun tabsFromShare(texts: List<String>, subject: String?): List<Tab> {
    val urls = texts.flatMap { extractUrls(it) }.distinct()
    val title = subject?.trim()?.takeIf { it.isNotEmpty() && urls.size == 1 }
    return urls.map { Tab(it, title) }
}
