package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceRulesTest {
    private val now = 1_700_000_000_000L
    private val admin = Peer("adm", "Fred's PC", DeviceKind.PC)

    @Test fun canTakeOverAfter30Days() {
        val day = 24L * 60 * 60 * 1000
        fun space(seen: Long) = Space("s", "S", "adm", listOf(admin), seen)
        assertFalse(canTakeOver(space(now - 30 * day), now))
        assertTrue(canTakeOver(space(now - 30 * day - 1), now))
        assertFalse(canTakeOver(space(now), now))
    }
}
