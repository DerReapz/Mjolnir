package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The room calls against the real relay routes. */
class RelayClientRoomTest {
    @Test fun openCurrentEnd() = clientTest {
        val owner = newDevice("Me", DeviceKind.PC)
        assertNull(owner.api.currentRoom())
        val room = owner.api.openRoom()
        val status = owner.api.currentRoom()!!
        assertEquals(room.roomId, status.roomId)
        assertEquals(room.expiresAtEpochMs, status.expiresAtEpochMs)
        assertNull(status.guest)
        assertKind(RelayErrorKind.ALREADY_SENT) { owner.api.openRoom() }
        owner.api.endRoom(room.roomId)
        assertNull(owner.api.currentRoom())
        assertKind(RelayErrorKind.NOT_FOUND) { owner.api.endRoom(room.roomId) }
    }
}
