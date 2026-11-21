@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.mjolnir.desktop

import app.mjolnir.client.*
import app.mjolnir.protocol.*
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class SpacesControllerTest {
    private val admin = Peer("adm", "Fred's PC", DeviceKind.PC)
    private val now = 1_700_000_000_000L

    private fun TestScope.controller(api: RelayApi, onChanged: () -> Unit = {}): Pair<SpacesController, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return SpacesController({ api }, scope, { now }, onChanged) to scope
    }

    @Test fun createRefreshesList() = runTest {
        val api = FakeRelayApi()
        val (c, scope) = controller(api)
        c.create("Friends"); advanceUntilIdle()
        assertEquals(listOf("Friends"), c.spaces.value.map { it.name })
        scope.cancel()
    }

    @Test fun inviteShowsCodeAndFreshCodeAfterReject() = runTest {
        val api = FakeRelayApi()
        val (c, scope) = controller(api)
        c.create("Friends"); advanceUntilIdle()
        val id = c.spaces.value.single().id
        c.invite(id); advanceUntilIdle()
        assertEquals(InviteUi(id, "ABC123", now + Limits.PAIRING_TTL_MS), c.invite.value)

        api.pairingCodeResult = PairingCodeResponse("XYZ789", Long.MAX_VALUE)
        c.onJoinRequestHandled(id, accepted = false); advanceUntilIdle()
        assertEquals("XYZ789", c.invite.value?.code)

        c.onJoinRequestHandled(id, accepted = true); advanceUntilIdle() // the code was used up by the accepted joiner
        assertNull(c.invite.value)
        scope.cancel()
    }

    @Test fun joinSuccessShowsJoined() = runTest {
        val api = FakeRelayApi().apply {
            onClaim = { ClaimResponse("p1", admin, CodeKind.SPACE_INVITE, SpaceRef("s1", "Friends")) }
            statuses += PairingStatus(PairingState.ACCEPTED, admin, SpaceRef("s1", "Friends"))
        }
        val (c, scope) = controller(api)
        c.join("abc-234"); advanceUntilIdle()
        assertEquals(JoinUi.Joined("Friends"), c.join.value)
        assertEquals(listOf(CodeKind.SPACE_INVITE), api.claimExpects)
        scope.cancel()
    }

    @Test fun joinShowsWaitingForTheAdmin() = runTest {
        val api = FakeRelayApi().apply { onClaim = { ClaimResponse("p1", admin, CodeKind.SPACE_INVITE, SpaceRef("s1", "Friends")) } }
        val (c, scope) = controller(api)
        c.join("abc-234"); testScheduler.runCurrent()
        assertEquals(JoinUi.Waiting("Fred's PC"), c.join.value)
        scope.cancel()
    }

    @Test fun joinWrongKindShowsCopy() = runTest {
        val api = FakeRelayApi().apply { onClaim = { throw RelayException(RelayErrorKind.INVALID, "wrong_kind_pairing") } }
        val (c, scope) = controller(api)
        c.join("abc-234"); advanceUntilIdle()
        assertEquals(JoinUi.Failed("That's a pairing code. Use Pair a device."), c.join.value)
        scope.cancel()
    }

    @Test fun deletedSpaceClearsInvite() = runTest {
        val api = FakeRelayApi()
        val (c, scope) = controller(api)
        c.create("Friends"); advanceUntilIdle()
        val ref = c.spaces.value.single().let { SpaceRef(it.id, it.name) }
        c.invite(ref.id); advanceUntilIdle()
        assertNotNull(c.invite.value)
        api.spaceList.clear()
        c.onSpaceDeleted(ref); advanceUntilIdle()
        assertNull(c.invite.value)
        assertTrue(c.spaces.value.isEmpty())

        // an invite for a space that a refresh no longer lists goes too
        api.createSpace("Other"); c.refresh(); advanceUntilIdle()
        c.invite(api.spaceList.single().id); advanceUntilIdle()
        assertNotNull(c.invite.value)
        api.spaceList.clear(); c.refresh(); advanceUntilIdle()
        assertNull(c.invite.value)
        scope.cancel()
    }

    @Test fun adminActionsUpdateTheListAndNotifyPeers() = runTest {
        val api = FakeRelayApi()
        var changed = 0
        val (c, scope) = controller(api) { changed++ }
        c.create("Friends"); advanceUntilIdle()
        val id = c.spaces.value.single().id
        c.rename(id, "Pals"); advanceUntilIdle()
        assertEquals("Pals", c.spaces.value.single().name)
        c.delete(id); advanceUntilIdle()
        assertTrue(c.spaces.value.isEmpty())
        assertTrue(changed >= 2)
        scope.cancel()
    }

    @Test fun memberCanLeaveAndTakeOver() = runTest {
        val api = FakeRelayApi().apply { asMemberOf("s1", admin, adminSilent = true) }
        val (c, scope) = controller(api)
        c.refresh(); advanceUntilIdle()
        c.takeOver("s1"); advanceUntilIdle()
        assertEquals("dev-1", c.spaces.value.single().adminId)
        c.makeAdmin("s1", "dev-1"); advanceUntilIdle()
        api.addMember("s1", Peer("zed", "Zed", DeviceKind.PHONE))
        c.makeAdmin("s1", "zed"); advanceUntilIdle()
        assertEquals("zed", c.spaces.value.single().adminId)
        c.leave("s1"); advanceUntilIdle()
        assertTrue(c.spaces.value.isEmpty())
        scope.cancel()
    }

    @Test fun failureSurfacesAsANotice() = runTest {
        val api = FakeRelayApi().apply { failAllWith = RelayException(RelayErrorKind.NETWORK, "offline") }
        val (c, scope) = controller(api)
        c.create("Friends"); advanceUntilIdle()
        assertEquals("Can't reach the relay. Check your connection.", c.notice.value)
        c.dismissNotice()
        assertNull(c.notice.value)
        scope.cancel()
    }

    @Test fun backgroundRefreshFailureIsSilentAndKeepsTheList() = runTest {
        val api = FakeRelayApi()
        val (c, scope) = controller(api)
        c.create("Friends"); advanceUntilIdle()
        api.failAllWith = RelayException(RelayErrorKind.NETWORK, "offline")
        c.refresh(); advanceUntilIdle()
        assertNull(c.notice.value)
        assertEquals(listOf("Friends"), c.spaces.value.map { it.name })
        api.failAllWith = null
        api.createSpace("Work"); c.refresh(); advanceUntilIdle() // retried on the next event
        assertEquals(listOf("Friends", "Work"), c.spaces.value.map { it.name })
        scope.cancel()
    }

    @Test fun createAtTheSpaceLimitSaysSo() = runTest {
        val fake = FakeRelayApi()
        val api = object : RelayApi by fake {
            override suspend fun createSpace(name: String): Space = throw RelayException(RelayErrorKind.ALREADY_SENT, "space_limit")
        }
        val (c, scope) = controller(api)
        c.create("Friends"); advanceUntilIdle()
        assertEquals("You're in the most spaces allowed (50).", c.notice.value)
        scope.cancel()
    }
}
