package app.mjolnir.android.ui

import app.mjolnir.client.LocalUpload
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.SpaceRef
import kotlin.test.Test
import kotlin.test.assertEquals

class OutgoingTargetTest {
    private val up = LocalUpload("/outbox/a.txt", "a.txt", "text/plain", 5, uploadId = "up", ref = null)
    private fun item(spaceId: String?, spaceName: String?) =
        OutgoingFiles("o1", "pc1", "Fred", listOf(up), createdAtEpochMs = 0, spaceId = spaceId, spaceName = spaceName)

    @Test fun showsTheSpaceOfALiveCard() {
        val state = ThrowUiState(targets = listOf(Peer("pc1", "Fred", DeviceKind.PC, SpaceRef("s1", "Friends (renamed)"))))
        assertEquals("Fred · Friends (renamed)", outgoingTarget(state, item("s1", "Friends")))
    }

    @Test fun keepsTheSpaceLabelWhenTheSpaceLeftTheList() {
        assertEquals("Fred · Friends", outgoingTarget(ThrowUiState(), item("s1", "Friends")))
    }

    @Test fun noLabelForAPairedThrow() {
        assertEquals("Fred", outgoingTarget(ThrowUiState(), item(null, null)))
    }
}
