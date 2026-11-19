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
    // R5: valid tab URLs that java.net.URI rejects as typed are still opened, as an equivalent encoded address
    @Test fun opensR5UrlsThatUriRejectsAsTyped() = runTest {
        val opened = mutableListOf<String>(); val raw = mutableListOf<String>()
        val o = TabOpener(browse = { opened += it.toString() }, pause = {}, browseRaw = { raw += it })
        val urls = listOf(
            "https://a.com/search?q=a|b",
            "https://a.com/t?var={a,b}",
            "https://a.com/^x",
            "https://a.com/p%zz",
            "https://a.com/a%20b?x=[1]#frag#more",
            "https://a.com/%",
            "https://例え.jp/パス?q=\"x\"",
        )
        assertEquals(urls.size, o.open(urls.map { Tab(it) }))
        assertEquals(
            listOf(
                "https://a.com/search?q=a%7Cb",
                "https://a.com/t?var=%7Ba,b%7D",
                "https://a.com/%5Ex",
                "https://a.com/p%25zz",
                "https://a.com/a%20b?x=%5B1%5D#frag%23more",
                "https://a.com/%25",
                "https://例え.jp/パス?q=%22x%22",
            ),
            opened,
        )
        assertTrue(raw.isEmpty())
    }
    // a URL java.net.URI still cannot parse goes to the raw fallback instead of being lost
    @Test fun unparsableSafeUrlFallsBackToRawOpen() = runTest {
        val raw = mutableListOf<String>()
        val o = TabOpener(browse = { fail("unexpected $it") }, pause = {}, browseRaw = { raw += it })
        assertEquals(1, o.open(listOf(Tab("https://a|b.com/"))))
        assertEquals(listOf("https://a|b.com/"), raw)
    }
}
