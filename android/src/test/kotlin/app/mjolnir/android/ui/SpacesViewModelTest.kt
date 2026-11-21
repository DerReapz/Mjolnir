package app.mjolnir.android.ui

import androidx.lifecycle.SavedStateHandle
import app.mjolnir.android.MainDispatcherRule
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Nicknames
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.SpaceRef
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SpacesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val api = FakeRelayApi()
    private val admin = Peer("adm", "Fred's PC", DeviceKind.PC)
    private val now = 1_700_000_000_000L
    private lateinit var nicknames: Nicknames
    private var affected = 0
    private var unauthorized = 0

    @Before fun setUp() {
        nicknames = Nicknames(JsonFileStore(File(tmp.root, "nicknames.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() })
    }

    private fun newVm(handle: SavedStateHandle = SavedStateHandle()) =
        SpacesViewModel(api, nicknames, handle, clock = { now }, onPeersAffected = { affected++ }, onUnauthorized = { unauthorized++ })

    private fun kotlinx.coroutines.test.TestScope.vmWithSpace(): Pair<SpacesViewModel, String> {
        val vm = newVm()
        vm.create("Friends"); runCurrent()
        return vm to vm.state.value.spaces.single().id
    }

    @Test fun createRefreshesList() = runTest {
        val vm = newVm()
        vm.create("Friends"); advanceUntilIdle()
        assertEquals(listOf("Friends"), vm.state.value.spaces.map { it.name })
    }

    @Test fun inviteShowsCodeThenFreshCodeAfterReject() = runTest {
        val (vm, id) = vmWithSpace()
        vm.invite(id); runCurrent()
        assertEquals(InviteUi(id, "ABC123", now + Limits.PAIRING_TTL_MS), vm.state.value.invite)

        val joiner = Peer("zed", "Zed's phone", DeviceKind.PHONE)
        api.events.emit(RelayEvent.PairRequested("p1", joiner, SpaceRef(id, "Friends"))); runCurrent()
        assertEquals(JoinRequest("p1", joiner, id, "Friends"), vm.state.value.request)

        api.pairingCodeResult = PairingCodeResponse("XYZ789", Long.MAX_VALUE)
        vm.answerRequest(accept = false); runCurrent()
        assertEquals(listOf("p1"), api.rejected)
        assertNull(vm.state.value.request)
        assertEquals("XYZ789", vm.state.value.invite?.code)

        api.events.emit(RelayEvent.PairRequested("p2", joiner, SpaceRef(id, "Friends"))); runCurrent()
        vm.answerRequest(accept = true); runCurrent() // the code was used up by the accepted joiner
        assertEquals(listOf("p2"), api.accepted)
        assertNull(vm.state.value.invite)
        assertNull(vm.state.value.request)
    }

    @Test fun failedAcceptShowsFreshCodeAndANotice() = runTest {
        val (vm, id) = vmWithSpace()
        vm.invite(id); runCurrent()
        api.events.emit(RelayEvent.PairRequested("p1", Peer("zed", "Zed", DeviceKind.PHONE), SpaceRef(id, "Friends"))); runCurrent()
        api.failAllWith = RelayException(RelayErrorKind.NETWORK, "offline")
        vm.answerRequest(accept = true); runCurrent()
        assertEquals("Can't reach the relay. Check your connection.", vm.state.value.notice)
        assertNull(vm.state.value.request)
    }

    @Test fun requestsForOtherSpacesOrPairingsAreIgnored() = runTest {
        val (vm, id) = vmWithSpace()
        vm.invite(id); runCurrent()
        val zed = Peer("zed", "Zed", DeviceKind.PHONE)
        api.events.emit(RelayEvent.PairRequested("p1", zed)); runCurrent()
        api.events.emit(RelayEvent.PairRequested("p2", zed, SpaceRef("other", "Other"))); runCurrent()
        assertNull(vm.state.value.request)
    }

    @Test fun joinSuccessShowsJoined() = runTest {
        api.onClaim = { ClaimResponse("p1", admin, CodeKind.SPACE_INVITE, SpaceRef("s1", "Friends")) }
        api.statuses += PairingStatus(PairingState.ACCEPTED, admin, SpaceRef("s1", "Friends"))
        val vm = newVm()
        vm.join("abc-234"); advanceUntilIdle()
        assertEquals(JoinUi.Joined("Friends"), vm.state.value.join)
        assertEquals(listOf(CodeKind.SPACE_INVITE), api.claimExpects)
        assertTrue(affected >= 1)
    }

    @Test fun joinShowsWaitingForTheAdmin() = runTest {
        api.onClaim = { ClaimResponse("p1", admin, CodeKind.SPACE_INVITE, SpaceRef("s1", "Friends")) }
        val vm = newVm()
        vm.join("abc-234"); runCurrent()
        assertEquals(JoinUi.Waiting("Fred's PC"), vm.state.value.join)
    }

    @Test fun joinWrongKindShowsCopy() = runTest {
        api.onClaim = { throw RelayException(RelayErrorKind.INVALID, "wrong_kind_pairing") }
        val vm = newVm()
        vm.join("abc-234"); advanceUntilIdle()
        assertEquals(JoinUi.Failed("That's a pairing code. Use Pair a device."), vm.state.value.join)
    }

    @Test fun joinAlreadyMemberNamesTheSpace() = runTest {
        api.onClaim = { throw RelayException(RelayErrorKind.ALREADY_SENT, "already_member", detail = "Friends") }
        val vm = newVm()
        vm.join("abc-234"); advanceUntilIdle()
        assertEquals(JoinUi.Failed("This device is already in Friends."), vm.state.value.join)
    }

    @Test fun deletedSpaceClearsInvite() = runTest {
        val (vm, id) = vmWithSpace()
        vm.invite(id); runCurrent()
        assertNotNull(vm.state.value.invite)
        api.spaceList.clear()
        vm.onSpaceDeleted(SpaceRef(id, "Friends")); runCurrent()
        assertNull(vm.state.value.invite)
        assertTrue(vm.state.value.spaces.isEmpty())

        // an invite for a space that a refresh no longer lists goes too
        api.createSpace("Other"); vm.refresh(); runCurrent()
        vm.invite(api.spaceList.single().id); runCurrent()
        assertNotNull(vm.state.value.invite)
        api.spaceList.clear(); vm.refresh(); runCurrent()
        assertNull(vm.state.value.invite)
    }

    @Test fun deletingYourOwnSpaceSaysNothingButAnotherAdminsDeletionDoes() = runTest {
        val (vm, id) = vmWithSpace()
        vm.delete(id); runCurrent()
        assertFalse(vm.onSpaceDeleted(SpaceRef(id, "Friends")))
        assertNull(vm.state.value.notice)
        assertTrue(vm.onSpaceDeleted(SpaceRef("s9", "Book club")))
        runCurrent()
        assertEquals("Book club was deleted.", vm.state.value.notice)
    }

    @Test fun canTakeOverAfter30Days() {
        val day = 24L * 60 * 60 * 1000
        fun space(seen: Long) = Space("s", "S", "adm", listOf(admin), seen)
        assertFalse(canTakeOver(space(now - 30 * day), now))
        assertTrue(canTakeOver(space(now - 30 * day - 1), now))
        assertFalse(canTakeOver(space(now), now))
    }

    @Test fun adminActionsUpdateTheListAndNotifyPeers() = runTest {
        val (vm, id) = vmWithSpace()
        vm.rename(id, "Pals"); runCurrent()
        assertEquals("Pals", vm.state.value.spaces.single().name)
        vm.delete(id); runCurrent()
        assertTrue(vm.state.value.spaces.isEmpty())
        assertTrue(affected >= 3)
    }

    @Test fun memberCanLeaveAndTakeOver() = runTest {
        api.asMemberOf("s1", admin, adminSilent = true)
        val vm = newVm()
        vm.refresh(); runCurrent()
        vm.takeOver("s1"); runCurrent()
        assertEquals("dev-1", vm.state.value.spaces.single().adminId)
        api.addMember("s1", Peer("zed", "Zed", DeviceKind.PHONE))
        vm.makeAdmin("s1", "zed"); runCurrent()
        assertEquals("zed", vm.state.value.spaces.single().adminId)
        vm.leave("s1"); runCurrent()
        assertTrue(vm.state.value.spaces.isEmpty())
    }

    @Test fun failureSurfacesAsANotice() = runTest {
        api.failAllWith = RelayException(RelayErrorKind.NETWORK, "offline")
        val vm = newVm()
        vm.create("Friends"); advanceUntilIdle()
        assertEquals("Can't reach the relay. Check your connection.", vm.state.value.notice)
        vm.dismissNotice()
        assertNull(vm.state.value.notice)
    }

    @Test fun unauthorizedIsPassedOn() = runTest {
        api.failAllWith = RelayException(RelayErrorKind.UNAUTHORIZED, "no")
        val vm = newVm()
        vm.create("Friends"); advanceUntilIdle()
        assertEquals(1, unauthorized)
    }

    @Test fun backgroundRefreshFailureIsSilentAndKeepsTheList() = runTest {
        val vm = newVm()
        vm.create("Friends"); advanceUntilIdle()
        api.failAllWith = RelayException(RelayErrorKind.NETWORK, "offline")
        vm.refresh(); advanceUntilIdle()
        assertNull(vm.state.value.notice)
        assertEquals(listOf("Friends"), vm.state.value.spaces.map { it.name })
        api.failAllWith = null
        api.createSpace("Work"); vm.refresh(); advanceUntilIdle()
        assertEquals(listOf("Friends", "Work"), vm.state.value.spaces.map { it.name })
    }

    @Test fun rotationKeepsInviteCode() = runTest {
        val handle = SavedStateHandle()
        val (first, id) = run { val vm = newVm(handle); vm.create("Friends"); runCurrent(); vm to vm.state.value.spaces.single().id }
        first.invite(id); runCurrent()
        val shown = first.state.value.invite
        assertNotNull(shown)

        val restored = newVm(handle) // the activity was recreated
        assertEquals(shown, restored.state.value.invite)
        runCurrent()
        // it listens again for the joiner
        val zed = Peer("zed", "Zed", DeviceKind.PHONE)
        api.events.emit(RelayEvent.PairRequested("p1", zed, SpaceRef(id, "Friends"))); runCurrent()
        assertEquals("p1", restored.state.value.request?.pairingId)

        restored.dismissInvite(); runCurrent()
        assertNull(newVm(handle).state.value.invite)
    }

    @Test fun anExpiredSavedInviteIsNotRestored() = runTest {
        val handle = SavedStateHandle(mapOf("inviteSpaceId" to "s1", "inviteCode" to "ABC123", "inviteExpiresAt" to now - 1))
        assertNull(newVm(handle).state.value.invite)
    }

    @Test fun confirmationsUseTheDesktopWording() = runTest {
        api.asMemberOf("s1", admin, name = "Friends")
        api.addMember("s1", Peer("zed", "Zed", DeviceKind.PHONE))
        nicknames.set("zed", "Zedd")
        val vm = newVm(); vm.refresh(); runCurrent()
        val space = vm.state.value.spaces.single()
        val zed = space.members.first { it.deviceId == "zed" }

        vm.askLeave(space)
        assertEquals(SpaceConfirm("Leave Friends?", "Leave space", SpaceAction.Leave("s1")), vm.state.value.confirm)
        vm.dismissConfirm(); assertNull(vm.state.value.confirm)
        vm.askDelete(space)
        assertEquals("Delete Friends?", vm.state.value.confirm?.title)
        vm.askRemove(space, zed)
        assertEquals("Remove Zedd from Friends?", vm.state.value.confirm?.title)
        vm.askMakeAdmin(space, zed)
        assertEquals("Make Zedd admin of Friends?", vm.state.value.confirm?.title)
        vm.askTakeOver(space)
        assertEquals(SpaceConfirm("Take over Friends?", "Take over as admin", SpaceAction.TakeOver("s1")), vm.state.value.confirm)
    }

    @Test fun confirmingRunsTheActionAndClosesTheDialog() = runTest {
        val (vm, _) = vmWithSpace()
        vm.askDelete(vm.state.value.spaces.single())
        vm.confirm(); runCurrent()
        assertNull(vm.state.value.confirm)
        assertTrue(vm.state.value.spaces.isEmpty())
    }

    @Test fun nicknameIsPrunedAfterTheRefreshedPeersNoLongerLinkTheDevice() = runTest {
        api.asMemberOf("s1", admin)
        nicknames.set("zed", "Zedd"); nicknames.set("pal", "Pal"); nicknames.set("shared", "Sam")
        api.peers += Peer("pal", "Pal's PC", DeviceKind.PC) // still paired
        api.peers += Peer("shared", "Sam", DeviceKind.PHONE, SpaceRef("s2", "Other space")) // linked through another space
        // "zed" is listed by nobody any more
        val vm = newVm(); vm.refresh(); runCurrent()
        vm.leave("s1"); runCurrent()
        assertEquals(mapOf("pal" to "Pal", "shared" to "Sam"), nicknames.changes.value)
    }

    @Test fun nicknameIsKeptWhileASharedSpaceStillLinksTheDevice() = runTest {
        api.asMemberOf("s1", admin)
        nicknames.set("zed", "Zedd")
        api.peers += Peer("zed", "Zed", DeviceKind.PHONE, SpaceRef("s2", "Other space"))
        val vm = newVm(); vm.refresh(); runCurrent()
        vm.leave("s1"); runCurrent()
        assertEquals(mapOf("zed" to "Zedd"), nicknames.changes.value)
    }
}
