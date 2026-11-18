package app.mjolnir.protocol

import kotlin.test.*

class UrlsTest {
    @Test fun acceptsHttpAndHttps() {
        listOf("https://example.com/a?b=c#d", "http://192.168.1.10:8080/", "HTTPS://Example.com")
            .forEach { assertTrue(isSafeTabUrl(it), it) }
    }
    @Test fun rejectsEverythingElse() {
        listOf("javascript:alert(1)", "file:///C:/Windows/System32", "ms-settings:", "intent://x#Intent;end",
            "ftp://example.com", "https://", "https:///path", "\\\\server\\share", "https://exa mple.com",
            " https://example.com", "https://example.com/" + "a".repeat(8192))
            .forEach { assertFalse(isSafeTabUrl(it), it) }
    }
    @Test fun extractsUrlsFromSharedText() {
        assertEquals(listOf("https://a.com/1", "https://b.com/2"), extractUrls("https://a.com/1\nhttps://b.com/2"))
        assertEquals(listOf("https://a.com/x"), extractUrls("Check this: https://a.com/x."))
        assertEquals(listOf("https://a.com/x"), extractUrls("(see https://a.com/x)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Thor_(god)"), extractUrls("https://en.wikipedia.org/wiki/Thor_(god)"))
        assertEquals(listOf("https://a.com/1"), extractUrls("https://a.com/1 and again https://a.com/1"))
        assertEquals(emptyList(), extractUrls("no links here, javascript:alert(1)"))
    }
    @Test fun cleanTabsDropsUnsafeDedupesAndTrimsTitles() {
        val out = cleanTabs(listOf(Tab("https://a.com", "A".repeat(400)), Tab("file:///etc/passwd"),
            Tab("https://a.com", "dup"), Tab("https://b.com", "   ")))
        assertEquals(listOf(Tab("https://a.com", "A".repeat(300)), Tab("https://b.com", null)), out)
    }
    @Test fun acceptsLenientRealWorldUrls() {
        listOf("https://a.com/?a=b|c", "https://a.com/?q[]=1", "https://a.com/?d=50%", "https://a.com/{x}^`y",
            "https://my_host.example.com/", "https://[::1]:8080/", "https://u:p@a.com:81/x",
            "https://a.com/" + "a".repeat(8192 - "https://a.com/".length))
            .forEach { assertTrue(isSafeTabUrl(it), it) }
        assertEquals(8192, ("https://a.com/" + "a".repeat(8192 - "https://a.com/".length)).length)
    }
    @Test fun extractStripsWrappers() {
        listOf("<https://a.com/x>", "[https://a.com/x]", "{https://a.com/x}", "(see https://a.com/x).")
            .forEach { assertEquals(listOf("https://a.com/x"), extractUrls(it), it) }
    }
}
