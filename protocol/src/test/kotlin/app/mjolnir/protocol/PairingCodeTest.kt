package app.mjolnir.protocol

import kotlin.test.*

class PairingCodeTest {
    @Test fun normalizesForgivingInput() {
        assertEquals("K7M2QX", PairingCode.normalize("k7m 2qx"))
        assertEquals("K7M2QX", PairingCode.normalize("K7M-2QX"))
        assertEquals("K7M2Q0", PairingCode.normalize("K7M-2QO"))
        assertEquals("K7M211", PairingCode.normalize("k7m-2il"))
        assertNull(PairingCode.normalize("K7M2Q")); assertNull(PairingCode.normalize("K7M2QXX")); assertNull(PairingCode.normalize("K7M2QU"))
    }
    @Test fun generatesCodesFromAlphabet() = repeat(1000) {
        val c = PairingCode.generate(); assertEquals(6, c.length); assertTrue(c.all { it in PairingCode.ALPHABET })
    }
    @Test fun displaysWithDash() = assertEquals("K7M-2QX", PairingCode.display("K7M2QX"))
}
