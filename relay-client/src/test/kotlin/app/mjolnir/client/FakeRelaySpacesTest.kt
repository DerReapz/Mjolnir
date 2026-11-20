package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FakeRelaySpacesTest {
    private val sam = Peer("sam", "Sam", DeviceKind.PHONE)

    @Test fun createRenameDeleteAndInvite() = runTest {
        val api = FakeRelayApi()
        val space = api.createSpace("Friends")
        assertEquals(api.me.deviceId, space.adminId)
        assertEquals("Crew", api.renameSpace(space.id, "Crew").name)
        assertEquals(listOf("Crew"), api.spaces().map { it.name })
        assertTrue(api.createInvite(space.id).code.isNotEmpty())
        api.deleteSpace(space.id)
        assertTrue(api.spaces().isEmpty())
        assertKind(RelayErrorKind.NOT_FOUND) { api.renameSpace(space.id, "X") }
        assertKind(RelayErrorKind.INVALID) { api.createSpace(" ") }
    }

    @Test fun membershipAndAdminRules() = runTest {
        val api = FakeRelayApi()
        val space = api.createSpace("Friends")
        api.addMember(space.id, sam)
        val leave = runCatching { api.leaveSpace(space.id) }.exceptionOrNull() as RelayException
        assertEquals("admin_must_transfer", leave.message)
        assertKind(RelayErrorKind.ALREADY_SENT) { api.takeOverAdmin(space.id) } // admin_active
        assertEquals("sam", api.transferAdmin(space.id, "sam").adminId)
        api.leaveSpace(space.id)
        assertTrue(api.spaces().isEmpty())
        // Now only a member: cannot remove, cannot transfer.
        api.asMemberOf("s2", sam)
        assertKind(RelayErrorKind.NOT_PAIRED) { api.removeMember("s2", "sam") }
        assertKind(RelayErrorKind.NOT_PAIRED) { api.transferAdmin("s2", api.me.deviceId) }
    }

    @Test fun takeOverWhenAdminIsSilent() = runTest {
        val api = FakeRelayApi()
        api.asMemberOf("s2", sam, adminSilent = true)
        assertEquals(api.me.deviceId, api.takeOverAdmin("s2").adminId)
    }
}
