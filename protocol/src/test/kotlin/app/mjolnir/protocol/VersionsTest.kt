package app.mjolnir.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionsTest {
    @Test fun compare() {
        assertTrue(Versions.isNewer("1.0.10", "1.0.9"))
        assertFalse(Versions.isNewer("1.0.9", "1.0.9"))
        assertFalse(Versions.isNewer("1.0.9", "1.0.10"))
        assertFalse(Versions.isNewer("x", "1.0.1"))
        assertFalse(Versions.isNewer("1.0.2", "x"))
        assertTrue(Versions.isNewer("1.1", "1.0.9"))
        assertEquals(listOf(1, 0), Versions.parse("1.0"))
        assertNull(Versions.parse("1..0"))
        assertNull(Versions.parse(""))
    }
}
