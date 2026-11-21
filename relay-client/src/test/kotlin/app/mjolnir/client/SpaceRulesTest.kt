package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceRulesTest {
    private val admin = Peer("adm", "Fred's PC", DeviceKind.PC)

    @Test fun canTakeOverOnlyWhenTheRelayReportsTheAdminSilent() {
        assertTrue(canTakeOver(Space("s", "S", "adm", listOf(admin), adminSilent = true)))
        assertFalse(canTakeOver(Space("s", "S", "adm", listOf(admin), adminSilent = false)))
        assertFalse(canTakeOver(Space("s", "S", "adm", listOf(admin)))) // an older relay omits the field
    }
}
