package app.mjolnir.client

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkifyTest {
    @Test fun linkifyHandlesTrailingPunctuation() {
        assertEquals(
            listOf(TextPart.Plain("see "), TextPart.Link("https://a.com/x"), TextPart.Plain(".")),
            linkify("see https://a.com/x."),
        )
        assertEquals(
            listOf(TextPart.Plain("("), TextPart.Link("https://a.com/x"), TextPart.Plain(") and "), TextPart.Link("http://b.org/y?q=1"), TextPart.Plain("!")),
            linkify("(https://a.com/x) and http://b.org/y?q=1!"),
        )
        assertEquals(listOf(TextPart.Link("https://a.com/w_(x)")), linkify("https://a.com/w_(x)"))
    }

    @Test fun linkifyLeavesPlainTextAndUnsafeUrlsAlone() {
        assertEquals(listOf(TextPart.Plain("no links here")), linkify("no links here"))
        assertEquals(emptyList(), linkify(""))
        assertEquals(listOf(TextPart.Plain("http:// nothing")), linkify("http:// nothing"))
        assertEquals(
            listOf(TextPart.Link("https://a.com"), TextPart.Plain("\n"), TextPart.Link("https://b.com")),
            linkify("https://a.com\nhttps://b.com"),
        )
    }
}
