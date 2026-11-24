package app.mjolnir.android.update

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartCheckGateTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun startChecksAtMostOnceAnHourAcrossProcesses() {
        val file = File(temp.root, "update-check.txt")
        var now = 10_000_000L
        fun gate() = StartCheckGate(file) { now } // a new process each time
        assertTrue(gate().checkNow()) // never checked
        assertEquals("10000000", file.readText())
        now += StartCheckGate.START_CHECK_GAP_MS - 1
        assertFalse(gate().checkNow()) // restarted within the hour: the 6-hour loop and pushes still run
        assertEquals("10000000", file.readText()) // a skipped check is not recorded
        now += 1
        assertTrue(gate().checkNow())
        now -= 5 * StartCheckGate.START_CHECK_GAP_MS // the clock went back: check rather than wait hours
        assertTrue(gate().checkNow())
        file.writeText("garbage")
        assertTrue(gate().checkNow())
    }
}
