package app.mjolnir.client

import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.Tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each space call against the real relay routes: paths, bodies and error mapping. */
class RelayClientSpacesTest {
    private suspend fun ClientTestScope.join(admin: TestDevice, spaceId: String, guest: TestDevice) {
        val code = admin.api.createInvite(spaceId).code
        val claim = guest.api.claimPairingCode(code, CodeKind.SPACE_INVITE)
        assertEquals(CodeKind.SPACE_INVITE, claim.kind)
        admin.api.acceptPairing(claim.pairingId)
        assertEquals(PairingState.ACCEPTED, guest.api.pairingStatus(claim.pairingId).state)
    }

    @Test fun createListRenameDelete() = clientTest {
        val admin = newDevice("Admin", DeviceKind.PC)
        val space = admin.api.createSpace("Friends")
        assertEquals("Friends", space.name); assertEquals(admin.id, space.adminId)
        assertEquals(listOf(space.id), admin.api.spaces().map { it.id })
        assertEquals("Crew", admin.api.renameSpace(space.id, "Crew").name)
        assertEquals("Crew", admin.api.spaces().single().name)
        admin.api.deleteSpace(space.id)
        assertTrue(admin.api.spaces().isEmpty())
    }

    @Test fun invalidNameAndNotFound() = clientTest {
        val admin = newDevice("Admin", DeviceKind.PC)
        assertKind(RelayErrorKind.INVALID) { admin.api.createSpace("  ") }
        assertKind(RelayErrorKind.NOT_FOUND) { admin.api.renameSpace("nope", "X") }
    }

    @Test fun inviteJoinSendAndPeers() = clientTest {
        val admin = newDevice("Admin", DeviceKind.PC); val guest = newDevice("Sam", DeviceKind.PHONE)
        val space = admin.api.createSpace("Friends")
        join(admin, space.id, guest)
        assertEquals(setOf(admin.id, guest.id), admin.api.spaces().single().members.map { it.deviceId }.toSet())
        val entry = guest.api.peers().single()
        assertEquals(admin.id, entry.deviceId); assertEquals(space.id, entry.space?.id)
        guest.api.send(admin.id, listOf(Tab("https://a.com")), spaceId = space.id)
        assertEquals("Friends", admin.api.inbox().single().space?.name)
        assertKind(RelayErrorKind.NOT_PAIRED) { guest.api.send(admin.id, listOf(Tab("https://a.com"))) }
    }

    @Test fun wrongKindClaimIsInvalidWithRelayCode() = clientTest {
        val admin = newDevice("Admin", DeviceKind.PC); val guest = newDevice("Sam", DeviceKind.PHONE)
        val space = admin.api.createSpace("Friends")
        val e = runCatching { guest.api.claimPairingCode(admin.api.createInvite(space.id).code) }.exceptionOrNull() as RelayException
        assertEquals(RelayErrorKind.INVALID, e.kind); assertEquals("wrong_kind_space", e.message)
    }

    @Test fun alreadyMemberCarriesSpaceName() = clientTest {
        val admin = newDevice("Admin", DeviceKind.PC); val guest = newDevice("Sam", DeviceKind.PHONE)
        val space = admin.api.createSpace("Friends")
        join(admin, space.id, guest)
        val e = runCatching { guest.api.claimPairingCode(admin.api.createInvite(space.id).code, CodeKind.SPACE_INVITE) }.exceptionOrNull() as RelayException
        assertEquals("already_member", e.message); assertEquals("Friends", e.detail)
        assertEquals(ClaimOutcome.Failed("This device is already in Friends."),
            claimAndAwait(guest.api, admin.api.createInvite(space.id).code, {}, expect = CodeKind.SPACE_INVITE))
    }

    @Test fun removeMemberLeaveAndAdminRules() = clientTest {
        val admin = newDevice("Admin", DeviceKind.PC); val a = newDevice("A", DeviceKind.PHONE); val b = newDevice("B", DeviceKind.PHONE)
        val space = admin.api.createSpace("Friends")
        join(admin, space.id, a); join(admin, space.id, b)
        // A plain member cannot remove another member (403 -> NOT_PAIRED).
        assertKind(RelayErrorKind.NOT_PAIRED) { a.api.removeMember(space.id, b.id) }
        admin.api.removeMember(space.id, b.id)
        assertTrue(b.api.spaces().isEmpty())
        // The admin cannot just leave (409 admin_must_transfer).
        val e = runCatching { admin.api.leaveSpace(space.id) }.exceptionOrNull() as RelayException
        assertEquals("admin_must_transfer", e.message)
        assertEquals(a.id, admin.api.transferAdmin(space.id, a.id).adminId)
        admin.api.leaveSpace(space.id)
        assertTrue(admin.api.spaces().isEmpty())
        // The admin was seen just now, so taking over is refused.
        val t = runCatching { a.api.takeOverAdmin(space.id) }.exceptionOrNull() as RelayException
        assertEquals("admin_active", t.message)
    }
}
