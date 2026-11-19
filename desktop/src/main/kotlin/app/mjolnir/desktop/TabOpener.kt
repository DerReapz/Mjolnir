package app.mjolnir.desktop

import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.isSafeTabUrl
import java.awt.Desktop
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class TabOpener(
    private val browse: (URI) -> Unit = ::browseWithSystem,
    private val pause: suspend () -> Unit = { delay(100) },
    /** Last resort for a safe URL that java.net.URI still rejects after [browsableUri]. */
    private val browseRaw: (String) -> Unit = ::browseWithRundll32,
) {
    /** Opens only http(s) tabs, in order; returns how many opened. One failure does not stop the rest. */
    suspend fun open(tabs: List<Tab>): Int {
        var opened = 0
        for (tab in tabs) {
            if (!isSafeTabUrl(tab.url)) continue
            try {
                val uri = try {
                    browsableUri(tab.url)
                } catch (e: URISyntaxException) {
                    null
                }
                if (uri != null) browse(uri) else browseRaw(tab.url)
                opened++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            pause()
        }
        return opened
    }
}

private const val HEX = "0123456789ABCDEFabcdef"
private const val ALLOWED_AFTER_AUTHORITY = "-._~!$&'()*+,;=:@/?"

/**
 * [url] as a [URI], percent-encoding the ASCII characters RFC 3986 does not allow after the authority (`|`, `{`, `^`,
 * `[`, a second `#`, …) and every `%` that does not start a valid `%XX` escape. Valid escapes, reserved characters
 * and the scheme and authority are kept as they are, so the browser gets the same address.
 */
fun browsableUri(url: String): URI {
    val schemeEnd = url.indexOf("://")
    val authorityEnd = if (schemeEnd < 0) 0
    else url.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd + 3).let { if (it < 0) url.length else it }
    val out = StringBuilder(url.length + 16).append(url, 0, authorityEnd)
    var seenHash = false
    var i = authorityEnd
    while (i < url.length) {
        val c = url[i]
        when {
            c.isLetterOrDigit() && c.code < 0x80 -> out.append(c)
            c in ALLOWED_AFTER_AUTHORITY -> out.append(c)
            c == '#' && !seenHash -> { seenHash = true; out.append(c) }
            c == '%' && i + 2 < url.length && url[i + 1] in HEX && url[i + 2] in HEX -> out.append(c)
            c.code >= 0x80 -> out.append(c) // java.net.URI accepts other Unicode characters as they are
            else -> out.append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
        }
        i++
    }
    return URI(out.toString())
}

fun browseWithSystem(uri: URI) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
        desktop.browse(uri)
    } else {
        browseWithRundll32(uri.toString())
    }
}

fun browseWithRundll32(url: String) {
    val exit = ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start().waitFor()
    check(exit == 0) { "rundll32 exited with $exit" }
}
