package app.mjolnir.desktop

import app.mjolnir.protocol.Tab
import java.net.URI
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class TabOpenerTest {
    // safe tabs open in order and are counted
    @Test fun opensSafeTabsInOrder() = runTest {
        val opened = mutableListOf<String>(); val o = TabOpener(browse = { opened += it.toString() }, pause = {})
        assertEquals(2, o.open(listOf(Tab("https://a.com"), Tab("https://b.com")))); assertEquals(listOf("https://a.com", "https://b.com"), opened)
    }
    // non-web schemes and paths are never handed to the browser
    @Test fun neverOpensNonWebUrls() = runTest {
        val opened = mutableListOf<URI>(); val o = TabOpener(browse = { opened += it }, pause = {})
        assertEquals(0, o.open(listOf("javascript:alert(1)", "file:///C:/x", "ms-settings:", "intent://x", "\\\\host\\share").map { Tab(it) }))
        assertTrue(opened.isEmpty())
    }
    // a throwing browse call is skipped, the rest still open
    @Test fun oneBrowseFailureDoesNotStopTheRest() = runTest {
        val opened = mutableListOf<String>()
        val o = TabOpener(browse = { if (it.host == "a.com") error("boom"); opened += it.toString() }, pause = {})
        assertEquals(1, o.open(listOf(Tab("https://a.com"), Tab("https://b.com"))))
        assertEquals(listOf("https://b.com"), opened)
    }
}
