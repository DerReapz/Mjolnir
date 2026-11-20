package app.mjolnir.server

import app.mjolnir.protocol.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.*

private const val DAY = 86_400_000L

class SpaceRoutesTest {
    private suspend fun RelayTestScope.createSpaceResponse(d: RegisterResponse, name: String): HttpResponse =
        client.post("/v1/spaces") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(CreateSpaceRequest(name)) }

    private suspend fun RelayTestScope.createSpace(d: RegisterResponse, name: String = "Friends"): Space {
        val r = createSpaceResponse(d, name)
        check(r.status == HttpStatusCode.Created) { "create failed ${r.status}" }
        return r.body()
    }

    private suspend fun RelayTestScope.spaces(d: RegisterResponse): List<Space> =
        client.get("/v1/spaces") { bearerAuth(d.token) }.body()

    private suspend fun RelayTestScope.inviteCode(admin: RegisterResponse, spaceId: String): String {
        val r = client.post("/v1/spaces/$spaceId/invites") { bearerAuth(admin.token) }
        check(r.status == HttpStatusCode.Created) { "invite failed ${r.status}" }
        return r.body<PairingCodeResponse>().code
    }

    private suspend fun RelayTestScope.claimInvite(d: RegisterResponse, code: String): HttpResponse =
        client.post("/v1/pairings/claim") {
            bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(ClaimRequest(code, CodeKind.SPACE_INVITE))
        }

    private suspend fun RelayTestScope.accept(owner: RegisterResponse, pairingId: String) =
        client.post("/v1/pairings/$pairingId/accept") { bearerAuth(owner.token) }.status

    /** Admin invites [joiner]; claim and accept. */
    private suspend fun RelayTestScope.join(admin: RegisterResponse, space: Space, joiner: RegisterResponse) {
        val claim = claimInvite(joiner, inviteCode(admin, space.id))
        check(claim.status == HttpStatusCode.Accepted) { "claim failed ${claim.status}" }
        check(accept(admin, claim.body<ClaimResponse>().pairingId) == HttpStatusCode.NoContent)
    }

    private suspend fun RelayTestScope.sendIn(from: RegisterResponse, to: String, spaceId: String?, url: String = "https://a.com"): HttpResponse =
        client.post("/v1/send") {
            bearerAuth(from.token); contentType(ContentType.Application.Json)
            setBody(SendRequest(to, listOf(Tab(url)), spaceId = spaceId))
        }

    @Test fun createListAndNameValidation() = relayTest {
        val a = register("A")
        assertEquals(HttpStatusCode.BadRequest, createSpaceResponse(a, "   ").status)
        assertEquals(HttpStatusCode.BadRequest, createSpaceResponse(a, "x".repeat(41)).status)
        val s = createSpace(a, " Friends ")
        assertEquals("Friends", s.name); assertEquals(a.deviceId, s.adminId)
        assertEquals(listOf(a.deviceId), s.members.map { it.deviceId })
        assertEquals(listOf(s), spaces(a))
    }

    @Test fun nonAdminCannotInviteRemoveRenameDeleteTransfer() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C")
        val s = createSpace(a); join(a, s, b); join(a, s, c)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/spaces/${s.id}/invites") { bearerAuth(b.token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/v1/spaces/${s.id}/members/${c.deviceId}") { bearerAuth(b.token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.patch("/v1/spaces/${s.id}") {
            bearerAuth(b.token); contentType(ContentType.Application.Json); setBody(RenameSpaceRequest("Mine"))
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/v1/spaces/${s.id}") { bearerAuth(b.token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/spaces/${s.id}/admin") {
            bearerAuth(b.token); contentType(ContentType.Application.Json); setBody(TransferAdminRequest(b.deviceId))
        }.status)
        val after = spaces(a).single()
        assertEquals("Friends", after.name); assertEquals(a.deviceId, after.adminId); assertEquals(3, after.members.size)
    }

    @Test fun nonMemberGets404OnEverySpaceRoute() = relayTest {
        val a = register("A"); val out = register("Out")
        val s = createSpace(a)
        val json = ContentType.Application.Json
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/spaces/${s.id}/invites") { bearerAuth(out.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/spaces/${s.id}") { bearerAuth(out.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/spaces/${s.id}/members/${a.deviceId}") { bearerAuth(out.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/spaces/${s.id}/takeover") { bearerAuth(out.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.patch("/v1/spaces/${s.id}") {
            bearerAuth(out.token); contentType(json); setBody(RenameSpaceRequest("X"))
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/spaces/${s.id}/admin") {
            bearerAuth(out.token); contentType(json); setBody(TransferAdminRequest(out.deviceId))
        }.status)
        assertTrue(spaces(out).isEmpty())
    }

    @Test fun adminRenamesAndRemoves() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        val renamed = client.patch("/v1/spaces/${s.id}") {
            bearerAuth(a.token); contentType(ContentType.Application.Json); setBody(RenameSpaceRequest("Crew"))
        }
        assertEquals(HttpStatusCode.OK, renamed.status); assertEquals("Crew", renamed.body<Space>().name)
        assertEquals(HttpStatusCode.BadRequest, client.patch("/v1/spaces/${s.id}") {
            bearerAuth(a.token); contentType(ContentType.Application.Json); setBody(RenameSpaceRequest(""))
        }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${s.id}/members/${b.deviceId}") { bearerAuth(a.token) }.status)
        assertTrue(spaces(b).isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/spaces/${s.id}/members/${b.deviceId}") { bearerAuth(a.token) }.status)
    }

    @Test fun memberCanLeaveItself() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${s.id}/members/${b.deviceId}") { bearerAuth(b.token) }.status)
        assertEquals(listOf(a.deviceId), spaces(a).single().members.map { it.deviceId })
    }

    @Test fun joinFlowAddsMemberAndNotifiesAll() = relayTest {
        val a = register("A", DeviceKind.PC); val b = register("B"); val c = register("C")
        val s = createSpace(a); join(a, s, b)
        val code = inviteCode(a, s.id)
        withEvents(a) { val ea = this; withEvents(b) { val eb = this; withEvents(c) { val ec = this
            val claim = claimInvite(c, code)
            assertEquals(HttpStatusCode.Accepted, claim.status)
            val body = claim.body<ClaimResponse>()
            assertEquals(CodeKind.SPACE_INVITE, body.kind); assertEquals(SpaceRef(s.id, "Friends"), body.space)
            val req = assertIs<RelayEvent.PairRequested>(ea.next())
            assertEquals(SpaceRef(s.id, "Friends"), req.space); assertEquals("C", req.from.name)
            // Not a member until the admin accepts.
            assertTrue(spaces(c).isEmpty())
            assertEquals(HttpStatusCode.NoContent, accept(a, body.pairingId))
            assertEquals(RelayEvent.SpaceChanged(s.id), ea.next())
            assertEquals(RelayEvent.SpaceChanged(s.id), eb.next())
            assertEquals(RelayEvent.SpaceChanged(s.id), ec.next())
        } } }
        assertEquals(setOf(a.deviceId, b.deviceId, c.deviceId), spaces(c).single().members.map { it.deviceId }.toSet())
        // Joining a space does not pair the devices.
        assertFalse(store.areLinked(a.deviceId, c.deviceId))
    }

    @Test fun rejectedInviteAddsNobody() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a)
        val claim = claimInvite(b, inviteCode(a, s.id)).body<ClaimResponse>()
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/pairings/${claim.pairingId}/reject") { bearerAuth(a.token) }.status)
        assertTrue(spaces(b).isEmpty())
    }

    @Test fun joinerSeesSpaceInPairingStatus() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a)
        val claim = claimInvite(b, inviteCode(a, s.id)).body<ClaimResponse>()
        accept(a, claim.pairingId)
        val st = status(b, claim.pairingId)
        assertEquals(PairingState.ACCEPTED, st.state); assertEquals(SpaceRef(s.id, "Friends"), st.space)
    }

    @Test fun wrongKindErrors() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a)
        val invite = inviteCode(a, s.id)
        val pairing = createCode(a)
        assertEquals(HttpStatusCode.BadRequest, claimStatus(b, invite)) // default expect = PAIRING
        assertEquals("wrong_kind_space", client.post("/v1/pairings/claim") {
            bearerAuth(b.token); contentType(ContentType.Application.Json); setBody(ClaimRequest(invite))
        }.body<ErrorResponse>().error)
        val r = claimInvite(b, pairing)
        assertEquals(HttpStatusCode.BadRequest, r.status); assertEquals("wrong_kind_pairing", r.body<ErrorResponse>().error)
        // A wrong-kind attempt must not consume either code.
        assertEquals(HttpStatusCode.Accepted, claimInvite(b, invite).status)
        assertEquals(HttpStatusCode.Accepted, claimStatus(register("C"), pairing))
    }

    @Test fun alreadyMember() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        val r = claimInvite(b, inviteCode(a, s.id))
        assertEquals(HttpStatusCode.Conflict, r.status); assertEquals("already_member", r.body<ErrorResponse>().error)
    }

    @Test fun adminClaimingOwnInviteIsOwnCode() = relayTest {
        val a = register("A")
        val s = createSpace(a)
        val r = claimInvite(a, inviteCode(a, s.id))
        assertEquals(HttpStatusCode.BadRequest, r.status); assertEquals("own_code", r.body<ErrorResponse>().error)
    }

    @Test fun inviteForDeletedSpaceIsNotFound() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a)
        val code = inviteCode(a, s.id)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${s.id}") { bearerAuth(a.token) }.status)
        assertEquals(HttpStatusCode.NotFound, claimInvite(b, code).status)
    }

    @Test fun claimWaitingForDeletedSpaceCannotBeAccepted() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a)
        val claim = claimInvite(b, inviteCode(a, s.id)).body<ClaimResponse>()
        client.delete("/v1/spaces/${s.id}") { bearerAuth(a.token) }
        assertEquals(HttpStatusCode.NotFound, accept(a, claim.pairingId))
        assertTrue(spaces(b).isEmpty())
    }

    @Test fun oldAdminsInviteDiesWhenAdminChanges() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C")
        val s = createSpace(a); join(a, s, b)
        val code = inviteCode(a, s.id)
        val pending = claimInvite(c, code).body<ClaimResponse>()
        val d = register("D")
        val code2 = inviteCode(a, s.id)
        assertEquals(HttpStatusCode.OK, client.post("/v1/spaces/${s.id}/admin") {
            bearerAuth(a.token); contentType(ContentType.Application.Json); setBody(TransferAdminRequest(b.deviceId))
        }.status)
        assertEquals(HttpStatusCode.NotFound, claimInvite(d, code2).status)
        assertEquals(HttpStatusCode.NotFound, accept(a, pending.pairingId))
        assertTrue(spaces(c).isEmpty())
    }

    @Test fun peersListsSpaceMembersOncePerSpaceAndPairedOnlyOnce() = relayTest {
        val me = register("Me"); val p = register("Paired"); val x = register("X"); val y = register("Y"); val z = register("Z")
        pair(me, p)
        val s1 = createSpace(me, "One"); val s2 = createSpace(me, "Two")
        join(me, s1, p); join(me, s1, x); join(me, s2, x); join(me, s2, y)
        createSpace(z, "Other") // I am not in it
        val peers = peers(me)
        assertEquals(
            setOf(
                Peer(p.deviceId, "Paired", DeviceKind.PHONE),
                Peer(x.deviceId, "X", DeviceKind.PHONE, SpaceRef(s1.id, "One")),
                Peer(x.deviceId, "X", DeviceKind.PHONE, SpaceRef(s2.id, "Two")),
                Peer(y.deviceId, "Y", DeviceKind.PHONE, SpaceRef(s2.id, "Two")),
            ),
            peers.toSet(),
        )
        assertEquals(4, peers.size)
        assertTrue(peers.none { it.deviceId == me.deviceId || it.deviceId == z.deviceId })
    }

    @Test fun unpairSpaceOnlyPeerIs404() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/peers/${b.deviceId}") { bearerAuth(a.token) }.status)
        assertEquals(2, spaces(a).single().members.size)
        assertEquals(1, peers(a).size)
    }

    @Test fun sendWithSharedSpaceAllowed() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        assertEquals(HttpStatusCode.Accepted, sendIn(b, a.deviceId, s.id).status)
        assertEquals(HttpStatusCode.Accepted, sendIn(a, b.deviceId, s.id).status)
    }

    @Test fun sendWithoutSharedSpaceIs403() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C")
        val s = createSpace(a); join(a, s, b)
        val other = createSpace(c, "Other")
        // Same space members, but no spaceId: space membership alone does not authorise a bare send.
        assertEquals(HttpStatusCode.Forbidden, sendIn(a, b.deviceId, null).status)
        // Claimed space that the sender is not in, or the recipient is not in.
        assertEquals(HttpStatusCode.Forbidden, sendIn(a, b.deviceId, other.id).status)
        assertEquals(HttpStatusCode.Forbidden, sendIn(a, c.deviceId, s.id).status)
        assertEquals(HttpStatusCode.Forbidden, sendIn(c, b.deviceId, s.id).status)
        assertEquals(HttpStatusCode.Forbidden, sendIn(a, b.deviceId, "no-such-space").status)
        assertTrue(inbox(b).isEmpty()); assertTrue(inbox(c).isEmpty())
    }

    @Test fun spaceIdOnPairedSendMustStillBeSharedSpace() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C")
        pair(a, b)
        val other = createSpace(c, "Other")
        assertEquals(HttpStatusCode.Forbidden, sendIn(a, b.deviceId, other.id).status)
        assertEquals(HttpStatusCode.Accepted, sendIn(a, b.deviceId, null).status)
    }

    @Test fun cannotSendToSelfThroughSpace() = relayTest {
        val a = register("A")
        val s = createSpace(a)
        assertEquals(HttpStatusCode.Forbidden, sendIn(a, a.deviceId, s.id).status)
    }

    @Test fun sendAfterRemovalIs403() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        assertEquals(HttpStatusCode.Accepted, sendIn(b, a.deviceId, s.id).status)
        client.delete("/v1/spaces/${s.id}/members/${b.deviceId}") { bearerAuth(a.token) }
        assertEquals(HttpStatusCode.Forbidden, sendIn(b, a.deviceId, s.id).status)
        assertEquals(HttpStatusCode.Forbidden, sendIn(a, b.deviceId, s.id).status)
    }

    @Test fun sendAfterSpaceDeletedIs403() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        client.delete("/v1/spaces/${s.id}") { bearerAuth(a.token) }
        assertEquals(HttpStatusCode.Forbidden, sendIn(b, a.deviceId, s.id).status)
    }

    @Test fun deliveryCarriesSpace() = relayTest {
        val a = register("A", DeviceKind.PC); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        pair(a, b)
        withEvents(a) {
            val sent = sendIn(b, a.deviceId, s.id).body<SendResponse>()
            val ev = assertIs<RelayEvent.DeliveryArrived>(next())
            assertEquals(SpaceRef(s.id, "Friends"), ev.delivery.space); assertEquals(sent.deliveryId, ev.delivery.id)
        }
        assertEquals(SpaceRef(s.id, "Friends"), inbox(a).single().space)
        // A paired send without spaceId carries no label.
        send(b, a.deviceId, listOf(Tab("https://b.com")))
        assertEquals(listOf(SpaceRef(s.id, "Friends"), null), inbox(a).map { it.space })
        // The label is the name at send time and survives leaving and renaming.
        client.patch("/v1/spaces/${s.id}") { bearerAuth(a.token); contentType(ContentType.Application.Json); setBody(RenameSpaceRequest("Crew")) }
        client.delete("/v1/spaces/${s.id}/members/${b.deviceId}") { bearerAuth(a.token) }
        assertEquals(SpaceRef(s.id, "Friends"), inbox(a).first().space)
    }

    @Test fun fileDeliveryCarriesSpace() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        val f = upload(b, "x.txt", "hi".toByteArray())
        val r = client.post("/v1/send") {
            bearerAuth(b.token); contentType(ContentType.Application.Json)
            setBody(SendRequest(a.deviceId, fileIds = listOf(f.id), spaceId = s.id))
        }
        assertEquals(HttpStatusCode.Accepted, r.status)
        assertEquals(SpaceRef(s.id, "Friends"), inbox(a).single().space)
        val f2 = upload(b, "y.txt", "hi".toByteArray())
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/send") {
            bearerAuth(b.token); contentType(ContentType.Application.Json)
            setBody(SendRequest(register("Out").deviceId, fileIds = listOf(f2.id), spaceId = s.id))
        }.status)
    }

    @Test fun adminLeaveRefused() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        val r = client.delete("/v1/spaces/${s.id}/members/${a.deviceId}") { bearerAuth(a.token) }
        assertEquals(HttpStatusCode.Conflict, r.status); assertEquals("admin_must_transfer", r.body<ErrorResponse>().error)
        assertEquals(2, spaces(a).single().members.size)
        // After handing over, the old admin can leave.
        val t = client.post("/v1/spaces/${s.id}/admin") {
            bearerAuth(a.token); contentType(ContentType.Application.Json); setBody(TransferAdminRequest(b.deviceId))
        }
        assertEquals(b.deviceId, t.body<Space>().adminId)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${s.id}/members/${a.deviceId}") { bearerAuth(a.token) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/v1/spaces/${s.id}/invites") { bearerAuth(b.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/spaces/${s.id}/invites") { bearerAuth(a.token) }.status)
    }

    @Test fun transferTargetMustBeMember() = relayTest {
        val a = register("A"); val out = register("Out")
        val s = createSpace(a)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/spaces/${s.id}/admin") {
            bearerAuth(a.token); contentType(ContentType.Application.Json); setBody(TransferAdminRequest(out.deviceId))
        }.status)
        assertEquals(a.deviceId, spaces(a).single().adminId)
    }

    @Test fun takeoverOnlyAfter30Days() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b) // a is seen now
        val r = client.post("/v1/spaces/${s.id}/takeover") { bearerAuth(b.token) }
        assertEquals(HttpStatusCode.Conflict, r.status); assertEquals("admin_active", r.body<ErrorResponse>().error)
        clock.now += 30 * DAY // exactly 30 days is not "more than"
        assertEquals(HttpStatusCode.Conflict, client.post("/v1/spaces/${s.id}/takeover") { bearerAuth(b.token) }.status)
        clock.now += 61_000 // b's own touch refreshed b; a stays silent
        val ok = client.post("/v1/spaces/${s.id}/takeover") { bearerAuth(b.token) }
        assertEquals(HttpStatusCode.OK, ok.status); assertEquals(b.deviceId, ok.body<Space>().adminId)
        // The deposed admin is an ordinary member now.
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/spaces/${s.id}/invites") { bearerAuth(a.token) }.status)
    }

    @Test fun adminCannotTakeOverItself() = relayTest {
        val a = register("A")
        val s = createSpace(a)
        clock.now += 40 * DAY
        assertEquals(HttpStatusCode.Conflict, client.post("/v1/spaces/${s.id}/takeover") { bearerAuth(a.token) }.status)
    }

    @Test fun spaceViewReportsAdminLastSeen() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        assertEquals(clock.now, spaces(b).single().adminLastSeenEpochMs)
    }

    @Test fun memberLimit() = relayTest(limits = RelayLimits(maxMembersPerSpace = 2)) {
        val a = register("A"); val b = register("B"); val c = register("C")
        val s = createSpace(a); join(a, s, b)
        val r = claimInvite(c, inviteCode(a, s.id))
        assertEquals(HttpStatusCode.Conflict, r.status); assertEquals("member_limit", r.body<ErrorResponse>().error)
        assertEquals(2, spaces(a).single().members.size)
    }

    @Test fun memberLimitAlsoHoldsAtAccept() = relayTest(limits = RelayLimits(maxMembersPerSpace = 2)) {
        val a = register("A"); val b = register("B"); val c = register("C")
        val s = createSpace(a)
        val first = claimInvite(b, inviteCode(a, s.id)).body<ClaimResponse>()
        val code2 = inviteCode(a, s.id) // does not replace the claimed invite
        val second = claimInvite(c, code2).body<ClaimResponse>()
        assertEquals(HttpStatusCode.NoContent, accept(a, first.pairingId))
        assertEquals(HttpStatusCode.Conflict, accept(a, second.pairingId))
        assertEquals(2, spaces(a).single().members.size)
    }

    @Test fun spaceLimit() = relayTest(limits = RelayLimits(maxSpacesPerDevice = 2)) {
        val a = register("A"); val b = register("B")
        createSpace(a, "1"); createSpace(a, "2")
        val r = createSpaceResponse(a, "3")
        assertEquals(HttpStatusCode.Conflict, r.status); assertEquals("space_limit", r.body<ErrorResponse>().error)
        // Joining counts too.
        val s1 = createSpace(b, "b1"); createSpace(b, "b2")
        val claim = claimInvite(a, inviteCode(b, s1.id))
        assertEquals(HttpStatusCode.Conflict, claim.status); assertEquals("space_limit", claim.body<ErrorResponse>().error)
    }

    @Test fun deleteNotifiesMembers() = relayTest {
        val a = register("A"); val b = register("B")
        val s = createSpace(a); join(a, s, b)
        withEvents(b) {
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${s.id}") { bearerAuth(a.token) }.status)
            assertEquals(RelayEvent.SpaceDeleted(SpaceRef(s.id, "Friends")), next())
        }
        assertTrue(spaces(a).isEmpty()); assertTrue(spaces(b).isEmpty())
    }

    @Test fun removeAndLeaveNotifyEveryone() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C")
        val s = createSpace(a); join(a, s, b); join(a, s, c)
        withEvents(b) { val eb = this; withEvents(c) { val ec = this
            client.delete("/v1/spaces/${s.id}/members/${c.deviceId}") { bearerAuth(a.token) }
            assertEquals(RelayEvent.SpaceChanged(s.id), ec.next()) // the removed device itself
            assertEquals(RelayEvent.SpaceChanged(s.id), eb.next())
        } }
    }

    @Test fun spaceRoutesRequireAuth() = relayTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/spaces").status)
    }

    @Test fun memberSelfLeaveNotifiesRemainingAndLeaver() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C")
        val s = createSpace(a); join(a, s, b); join(a, s, c)
        withEvents(a) { val ea = this; withEvents(c) { val ec = this
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${s.id}/members/${c.deviceId}") { bearerAuth(c.token) }.status)
            assertEquals(RelayEvent.SpaceChanged(s.id), ec.next())
            assertEquals(RelayEvent.SpaceChanged(s.id), ea.next())
        } }
    }

    @Test fun joinerSpaceLimitHoldsAtAccept() = relayTest(limits = RelayLimits(maxSpacesPerDevice = 2)) {
        val a = register("A"); val b = register("B"); val j = register("J")
        val s1 = createSpace(a, "1"); val s2 = createSpace(a, "2"); val s3 = createSpace(b, "3")
        val c1 = claimInvite(j, inviteCode(a, s1.id)).body<ClaimResponse>()
        val c2 = claimInvite(j, inviteCode(a, s2.id)).body<ClaimResponse>()
        val c3 = claimInvite(j, inviteCode(b, s3.id)).body<ClaimResponse>()
        assertEquals(HttpStatusCode.NoContent, accept(a, c1.pairingId))
        assertEquals(HttpStatusCode.NoContent, accept(a, c2.pairingId))
        val r = client.post("/v1/pairings/${c3.pairingId}/accept") { bearerAuth(b.token) }
        assertEquals(HttpStatusCode.Conflict, r.status); assertEquals("space_limit", r.body<ErrorResponse>().error)
        assertEquals(2, spaces(j).size)
    }

    @Test fun acceptInviteOfVanishedClaimerIsNotFound() = relayTest {
        val a = register("A"); val j = register("J")
        val s = createSpace(a)
        val claim = claimInvite(j, inviteCode(a, s.id)).body<ClaimResponse>()
        store.deleteDevice(j.deviceId)
        assertEquals(HttpStatusCode.NotFound, accept(a, claim.pairingId))
        assertEquals(1, spaces(a).single().members.size)
    }

    @Test fun spaceNamesRejectControlCharacters() = relayTest {
        val a = register("A")
        assertEquals(HttpStatusCode.BadRequest, createSpaceResponse(a, "Fri\nends").status)
        assertEquals(HttpStatusCode.BadRequest, createSpaceResponse(a, "Fri\u0007ends").status)
        val s = createSpace(a)
        val r = client.patch("/v1/spaces/${s.id}") {
            bearerAuth(a.token); contentType(ContentType.Application.Json); setBody(RenameSpaceRequest("a\tb"))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status); assertEquals("invalid_name", r.body<ErrorResponse>().error)
    }
}
