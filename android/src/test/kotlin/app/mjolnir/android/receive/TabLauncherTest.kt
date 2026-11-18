package app.mjolnir.android.receive

import app.mjolnir.protocol.Tab
import kotlin.test.assertEquals
import org.junit.Test

class TabLauncherTest {
    @Test fun safeUrlsFiltersBeforeLaunch() = assertEquals(listOf("https://a.com"),
        safeUrls(listOf(Tab("https://a.com"), Tab("intent://x#Intent;end"), Tab("file:///sdcard/x"))))
}
