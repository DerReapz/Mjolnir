package app.mjolnir.android.share

import app.mjolnir.protocol.Tab
import kotlin.test.assertEquals
import org.junit.Test

class ShareParsingTest {
    @Test fun singleLinkUsesSubjectAsTitle() = assertEquals(listOf(Tab("https://a.com/x", "Article")), tabsFromShare(listOf("https://a.com/x"), "Article"))
    @Test fun severalLinksHaveNoTitles() = assertEquals(listOf(Tab("https://a.com"), Tab("https://b.com")), tabsFromShare(listOf("https://a.com\nhttps://b.com"), "Tabs"))
    @Test fun linksInsideProseAreFound() = assertEquals(listOf(Tab("https://a.com/x", null)), tabsFromShare(listOf("Look: https://a.com/x."), null))
    @Test fun multipleTextsAreCombinedAndDeduped() = assertEquals(2, tabsFromShare(listOf("https://a.com", "https://b.com https://a.com"), null).size)
    @Test fun nonWebLinksIgnored() = assertEquals(emptyList(), tabsFromShare(listOf("file:///sdcard/x intent://y"), null))
}
