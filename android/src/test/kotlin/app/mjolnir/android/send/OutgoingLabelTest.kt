package app.mjolnir.android.send

import kotlin.test.assertEquals
import org.junit.Test

class OutgoingLabelTest {
    @Test fun labels() {
        assertEquals("Throwing a.jpg… 45%", outgoingLabel(true, 45, "a.jpg", false))
        assertEquals("Waiting for Wi-Fi", outgoingLabel(false, null, null, true))
        assertEquals("Waiting for a connection", outgoingLabel(false, null, null, false))
    }

    @Test fun runningWithoutProgressSaysThrowing() = assertEquals("Throwing…", outgoingLabel(true, null, null, false))

    @Test fun runningWinsOverWaiting() = assertEquals("Throwing a.jpg… 0%", outgoingLabel(true, 0, "a.jpg", true))

    @Test fun progressPercentFromBytes() {
        assertEquals(0, progressPercent(0, 0))
        assertEquals(45, progressPercent(45, 100))
        assertEquals(100, progressPercent(100, 100))
    }

    @Test fun currentFileIsTheOneBeingUploaded() {
        val sizes = listOf("Photos/a.jpg" to 10L, "Photos/b.jpg" to 10L, "c.txt" to 5L)
        assertEquals("a.jpg", currentFileName(sizes, 0))
        assertEquals("a.jpg", currentFileName(sizes, 9))
        assertEquals("b.jpg", currentFileName(sizes, 10))
        assertEquals("c.txt", currentFileName(sizes, 25))
    }
}

class ProgressThrottleTest {
    @Test fun firstUpdateGoesOut() = kotlin.test.assertTrue(shouldReportProgress(0, -1, 1_000, null))
    @Test fun samePercentIsSkipped() = kotlin.test.assertFalse(shouldReportProgress(5, 5, 10_000, 1_000))
    @Test fun newPercentWithin500msIsSkipped() = kotlin.test.assertFalse(shouldReportProgress(6, 5, 1_499, 1_000))
    @Test fun newPercentAfter500msGoesOut() = kotlin.test.assertTrue(shouldReportProgress(6, 5, 1_500, 1_000))
    @Test fun hundredPercentAlwaysGoesOut() = kotlin.test.assertTrue(shouldReportProgress(100, 99, 1_001, 1_000))
    @Test fun notificationIdsAreNeverZero() {
        assertEquals(0, "".hashCode())
        assertEquals(1, notificationId(""))
        assertEquals("x".hashCode(), notificationId("x"))
    }
}
