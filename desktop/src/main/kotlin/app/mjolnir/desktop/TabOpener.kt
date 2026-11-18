package app.mjolnir.desktop

import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.isSafeTabUrl
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class TabOpener(
    private val browse: (URI) -> Unit = ::browseWithSystem,
    private val pause: suspend () -> Unit = { delay(100) },
) {
    /** Opens only http(s) tabs, in order; returns how many opened. One failure does not stop the rest. */
    suspend fun open(tabs: List<Tab>): Int {
        var opened = 0
        for (tab in tabs) {
            if (!isSafeTabUrl(tab.url)) continue
            try {
                browse(URI(tab.url))
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

fun browseWithSystem(uri: URI) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
        desktop.browse(uri)
    } else {
        val exit = ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start().waitFor()
        check(exit == 0) { "rundll32 exited with $exit" }
    }
}
