package app.mjolnir.desktop.ui

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.SpaceRef
import kotlin.test.Test
import kotlin.test.assertEquals

class PairRequestTitleTest {
    private val phone = Peer("ph", "Pixel 8", DeviceKind.PHONE)

    @Test fun pairingKeepsItsTitle() = assertEquals("Pair with Pixel 8?", pairRequestTitle(RelayEvent.PairRequested("p", phone)))

    @Test fun spaceJoinNamesDeviceAndSpace() =
        assertEquals("Pixel 8 wants to join Friends", pairRequestTitle(RelayEvent.PairRequested("p", phone, SpaceRef("s1", "Friends"))))
}
