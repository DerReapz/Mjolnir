package app.mjolnir.server

import app.mjolnir.protocol.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.*

private const val DAY = 86_400_000L

class ChatRoutesTest {
    private class Cast(val admin: RegisterResponse, val owner: RegisterResponse, val member: RegisterResponse, val outsider: RegisterResponse, val space: Space)

    private suspend fun RelayTestScope.spaceWith(admin: RegisterResponse, vararg joiners: RegisterResponse): Space {
        val s = client.post("/v1/spaces") {
            bearerAuth(admin.token); contentType(ContentType.Application.Json); setBody(CreateSpaceRequest("Friends"))
        }.body<Space>()
        for (j in joiners) {
            val code = client.post("/v1/spaces/${s.id}/invites") { bearerAuth(admin.token) }.body<PairingCodeResponse>().code
            val claim = client.post("/v1/pairings/claim") {
                bearerAuth(j.token); contentType(ContentType.Application.Json); setBody(ClaimRequest(code, CodeKind.SPACE_INVITE))
            }.body<ClaimResponse>()
            check(client.post("/v1/pairings/${claim.pairingId}/accept") { bearerAuth(admin.token) }.status == HttpStatusCode.NoContent)
        }
        return s
    }

    /** Space with admin A, plus B (chat owner), C (space member), D (space member who never joins the chat). */
    private suspend fun RelayTestScope.cast(): Cast {
        val a = register("A"); val b = register("B"); val c = register("C"); val d = register("D")
        return Cast(a, b, c, d, spaceWith(a, b, c, d))
    }

    private suspend fun RelayTestScope.chatResponse(d: RegisterResponse, spaceId: String, name: String = "Trip"): HttpResponse =
        client.post("/v1/spaces/$spaceId/chats") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(CreateChatRequest(name)) }

    private suspend fun RelayTestScope.newChat(d: RegisterResponse, spaceId: String, name: String = "Trip"): ChatSummary {
        val r = chatResponse(d, spaceId, name)
        check(r.status == HttpStatusCode.Created) { "create chat ${r.status}" }
        return r.body()
    }

    private suspend fun RelayTestScope.invite(from: RegisterResponse, chatId: String, to: String): HttpResponse =
        client.post("/v1/chats/$chatId/invites") { bearerAuth(from.token); contentType(ContentType.Application.Json); setBody(InviteRequest(to)) }

    private suspend fun RelayTestScope.invites(d: RegisterResponse): List<ChatInvite> =
        client.get("/v1/chat-invites") { bearerAuth(d.token) }.body()

    private suspend fun RelayTestScope.act(d: RegisterResponse, path: String): HttpStatusCode =
        client.post(path) { bearerAuth(d.token) }.status

    /** Owner [owner] invites [who] (already approved when the owner invites) and [who] joins. */
    private suspend fun RelayTestScope.addMember(owner: RegisterResponse, chatId: String, who: RegisterResponse) {
        val inv = invite(owner, chatId, who.deviceId).body<ChatInvite>()
        check(act(who, "/v1/chat-invites/${inv.id}/join") == HttpStatusCode.OK)
    }

    private suspend fun RelayTestScope.post(d: RegisterResponse, chatId: String, text: String): HttpResponse =
        client.post("/v1/chats/$chatId/messages") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(PostMessageRequest(text)) }

    private suspend fun RelayTestScope.say(d: RegisterResponse, chatId: String, text: String): ChatMessage {
        val r = post(d, chatId, text)
        check(r.status == HttpStatusCode.Created) { "post ${r.status}" }
        return r.body()
    }

    private suspend fun RelayTestScope.syncResponse(d: RegisterResponse, chatId: String, since: Long = 0): HttpResponse =
        client.get("/v1/chats/$chatId/sync?sinceRev=$since") { bearerAuth(d.token) }

    private suspend fun RelayTestScope.sync(d: RegisterResponse, chatId: String, since: Long = 0): ChatSync = syncResponse(d, chatId, since).body()

    private suspend fun RelayTestScope.chats(d: RegisterResponse, spaceId: String): List<ChatSummary> =
        client.get("/v1/spaces/$spaceId/chats") { bearerAuth(d.token) }.body()

    private suspend fun RelayTestScope.leaveChat(d: RegisterResponse, chatId: String, target: String = d.deviceId): HttpResponse =
        client.delete("/v1/chats/$chatId/members/$target") { bearerAuth(d.token) }

    private suspend fun RelayTestScope.delMessage(d: RegisterResponse, chatId: String, id: String) =
        client.delete("/v1/chats/$chatId/messages/$id") { bearerAuth(d.token) }

    private suspend fun RelayTestScope.editMessage(d: RegisterResponse, chatId: String, id: String, text: String) =
        client.patch("/v1/chats/$chatId/messages/$id") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(EditMessageRequest(text)) }

    /** Every request an outsider could make against [chatId], each expected to answer 404 not_found. */
    private suspend fun RelayTestScope.assertHidden(d: RegisterResponse, chatId: String, msgId: String = "m") {
        val answers = listOf(
            client.patch("/v1/chats/$chatId") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(RenameChatRequest("X")) },
            client.delete("/v1/chats/$chatId") { bearerAuth(d.token) },
            invite(d, chatId, d.deviceId),
            client.post("/v1/chats/$chatId/owner") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(TransferOwnerRequest(d.deviceId)) },
            client.delete("/v1/chats/$chatId/members/${d.deviceId}") { bearerAuth(d.token) },
            client.post("/v1/chats/$chatId/join") { bearerAuth(d.token) },
            syncResponse(d, chatId),
            post(d, chatId, "hi"),
            editMessage(d, chatId, msgId, "x"),
            delMessage(d, chatId, msgId),
            client.post("/v1/chats/$chatId/read") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(ReadRequest(1)) },
        )
        answers.forEachIndexed { i, r ->
            assertEquals(HttpStatusCode.NotFound, r.status, "request #$i")
            assertEquals("not_found", r.body<ErrorResponse>().error, "request #$i")
        }
    }

    @Test fun nonMemberSpaceMemberSees404Everywhere() = relayTest(limits = RelayLimits(registrationsPerHourPerIp = 20)) {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        val m = say(k.owner, chat.id, "secret")
        assertHidden(k.outsider, chat.id, m.id)
        // The chat is also unknown to someone outside the space, and to a random id.
        val stranger = register("S")
        assertHidden(stranger, chat.id, m.id)
        assertHidden(k.outsider, "nonexistent", m.id)
        // The admin of a different space has no say here either.
        val otherAdmin = register("OtherAdmin"); spaceWith(otherAdmin)
        assertHidden(otherAdmin, chat.id, m.id)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/spaces/${k.space.id}/chats") { bearerAuth(stranger.token) }.status)
        assertEquals(HttpStatusCode.NotFound, chatResponse(stranger, k.space.id).status)
        // Nothing changed.
        assertEquals(listOf("secret"), sync(k.owner, chat.id).messages.map { it.text })
    }

    @Test fun listShowsOnlyOwnChatsAndAllForAdmin() = relayTest {
        val k = cast()
        val ownerChat = newChat(k.owner, k.space.id, "Owner chat")
        val memberChat = newChat(k.member, k.space.id, "Member chat")
        assertEquals(listOf(ownerChat.id), chats(k.owner, k.space.id).map { it.id })
        assertEquals(listOf(memberChat.id), chats(k.member, k.space.id).map { it.id })
        assertEquals(emptyList(), chats(k.outsider, k.space.id))
        val all = chats(k.admin, k.space.id)
        assertEquals(setOf(ownerChat.id, memberChat.id), all.map { it.id }.toSet())
        assertTrue(all.none { it.member })
        assertTrue(chats(k.owner, k.space.id).single().member)
        assertEquals("Friends", all.first().spaceName)
    }

    @Test fun chatNameUsesSpaceNameRule() = relayTest {
        val k = cast()
        assertEquals(HttpStatusCode.BadRequest, chatResponse(k.owner, k.space.id, "  ").status)
        assertEquals(HttpStatusCode.BadRequest, chatResponse(k.owner, k.space.id, "x".repeat(41)).status)
        assertEquals(HttpStatusCode.BadRequest, chatResponse(k.owner, k.space.id, "a\u0007b").status)
        assertEquals("Trim", newChat(k.owner, k.space.id, " Trim ").name)
    }

    @Test fun ownerInviteSkipsApproval() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        val inv = invite(k.owner, chat.id, k.member.deviceId)
        assertEquals(HttpStatusCode.Created, inv.status)
        assertEquals(InviteState.APPROVED, inv.body<ChatInvite>().state)
        assertEquals(1, invites(k.member).size)
        assertEquals(HttpStatusCode.NotFound, syncResponse(k.member, chat.id).status) // no access until Join
        assertEquals(HttpStatusCode.OK, act(k.member, "/v1/chat-invites/${invites(k.member).single().id}/join"))
        assertEquals(HttpStatusCode.OK, syncResponse(k.member, chat.id).status)
        assertEquals(emptyList(), invites(k.member))
    }

    @Test fun memberInviteNeedsApprovalThenJoin() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        k.owner.let { owner ->
            withEvents(owner) {
                val inv = invite(k.member, chat.id, k.outsider.deviceId).body<ChatInvite>()
                assertEquals(InviteState.PENDING_APPROVAL, inv.state)
                assertEquals(RelayEvent.ChatInvitesChanged, next())
                // The owner owes an approval; the invitee sees nothing yet and cannot join.
                assertEquals(listOf(inv.id), invites(owner).map { it.id })
                assertEquals(emptyList(), invites(k.outsider))
                assertEquals(HttpStatusCode.Conflict, act(k.outsider, "/v1/chat-invites/${inv.id}/join"))
                // Only the owner approves.
                assertEquals(HttpStatusCode.Forbidden, act(k.member, "/v1/chat-invites/${inv.id}/approve"))
                assertEquals(HttpStatusCode.Forbidden, act(k.admin, "/v1/chat-invites/${inv.id}/approve"))
                assertEquals(HttpStatusCode.Forbidden, act(k.outsider, "/v1/chat-invites/${inv.id}/approve"))
                assertEquals(HttpStatusCode.NoContent, act(owner, "/v1/chat-invites/${inv.id}/approve"))
                assertEquals(RelayEvent.ChatInvitesChanged, next())
                // Only the invitee joins, and only once approved.
                assertEquals(HttpStatusCode.Forbidden, act(k.member, "/v1/chat-invites/${inv.id}/join"))
                assertEquals(HttpStatusCode.NotFound, act(register("Z"), "/v1/chat-invites/${inv.id}/join"))
                assertEquals(HttpStatusCode.OK, act(k.outsider, "/v1/chat-invites/${inv.id}/join"))
                assertEquals(HttpStatusCode.OK, syncResponse(k.outsider, chat.id).status)
            }
        }
    }

    @Test fun pendingInviteIsNotAnnouncedToTheInvitee() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        withEvents(k.outsider) {
            val inv = invite(k.member, chat.id, k.outsider.deviceId).body<ChatInvite>() // pending approval
            assertEquals(HttpStatusCode.NoContent, act(k.owner, "/v1/chat-invites/${inv.id}/deny"))
            // A space-wide event afterwards proves no invite event was queued ahead of it for the invitee.
            client.patch("/v1/spaces/${k.space.id}") {
                bearerAuth(k.admin.token); contentType(ContentType.Application.Json); setBody(RenameSpaceRequest("New"))
            }
            assertEquals(RelayEvent.SpaceChanged(k.space.id), next())
        }
        // Once approved, the invitee is told.
        val again = invite(k.member, chat.id, k.outsider.deviceId).body<ChatInvite>()
        withEvents(k.outsider) {
            assertEquals(HttpStatusCode.NoContent, act(k.owner, "/v1/chat-invites/${again.id}/approve"))
            assertEquals(RelayEvent.ChatInvitesChanged, next())
        }
    }

    @Test fun denyDeclineExpiryGiveNoAccess() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        // Denied.
        val denied = invite(k.member, chat.id, k.outsider.deviceId).body<ChatInvite>()
        assertEquals(HttpStatusCode.Forbidden, act(k.member, "/v1/chat-invites/${denied.id}/deny"))
        assertEquals(HttpStatusCode.NoContent, act(k.owner, "/v1/chat-invites/${denied.id}/deny"))
        assertEquals(HttpStatusCode.Conflict, act(k.outsider, "/v1/chat-invites/${denied.id}/join"))
        assertEquals(HttpStatusCode.NotFound, syncResponse(k.outsider, chat.id).status)
        assertEquals(emptyList(), invites(k.owner)); assertEquals(emptyList(), invites(k.outsider))
        // Declined (after the owner's own invite).
        val declined = invite(k.owner, chat.id, k.outsider.deviceId).body<ChatInvite>()
        assertEquals(HttpStatusCode.Forbidden, act(k.owner, "/v1/chat-invites/${declined.id}/decline"))
        assertEquals(HttpStatusCode.NoContent, act(k.outsider, "/v1/chat-invites/${declined.id}/decline"))
        assertEquals(HttpStatusCode.Conflict, act(k.outsider, "/v1/chat-invites/${declined.id}/join"))
        assertEquals(HttpStatusCode.NotFound, syncResponse(k.outsider, chat.id).status)
        // Expired: approved, then 7 days pass.
        val old = invite(k.owner, chat.id, k.outsider.deviceId).body<ChatInvite>()
        clock.now += 7 * DAY + 1
        assertEquals(emptyList(), invites(k.outsider))
        assertEquals(HttpStatusCode.Conflict, act(k.outsider, "/v1/chat-invites/${old.id}/join"))
        assertEquals(HttpStatusCode.NotFound, syncResponse(k.outsider, chat.id).status)
    }

    @Test fun inviteeMustBeSpaceMember() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        val stranger = register("Stranger")
        val r = invite(k.owner, chat.id, stranger.deviceId)
        assertEquals(HttpStatusCode.BadRequest, r.status); assertEquals("not_in_space", r.body<ErrorResponse>().error)
        assertEquals(HttpStatusCode.BadRequest, invite(k.owner, chat.id, "nobody").status)
        // A guest is never in a space, so never invitable.
        val (_, guest) = guestOf(k.owner)
        assertEquals(HttpStatusCode.BadRequest, invite(k.owner, chat.id, guest.deviceId).status)
        // Already a member / already invited.
        assertEquals("already_member", invite(k.owner, chat.id, k.owner.deviceId).body<ErrorResponse>().error)
        assertEquals(HttpStatusCode.Created, invite(k.owner, chat.id, k.member.deviceId).status)
        val dup = invite(k.owner, chat.id, k.member.deviceId)
        assertEquals(HttpStatusCode.Conflict, dup.status); assertEquals("already_invited", dup.body<ErrorResponse>().error)
        // Only chat members invite (the admin must join first).
        assertEquals(HttpStatusCode.Forbidden, invite(k.admin, chat.id, k.outsider.deviceId).status)
    }

    @Test fun inviteDiesWhenInviterLeavesSpace() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val inv = invite(k.member, chat.id, k.outsider.deviceId).body<ChatInvite>()
        assertEquals(1, invites(k.owner).size)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${k.space.id}/members/${k.member.deviceId}") { bearerAuth(k.member.token) }.status)
        assertEquals(emptyList(), invites(k.owner))
        assertEquals(HttpStatusCode.NotFound, act(k.owner, "/v1/chat-invites/${inv.id}/approve")) // gone
        assertEquals(HttpStatusCode.NotFound, act(k.outsider, "/v1/chat-invites/${inv.id}/join"))
        assertEquals(HttpStatusCode.NotFound, syncResponse(k.outsider, chat.id).status)
        // The leaver lost the chat too.
        assertEquals(listOf(k.owner.deviceId), sync(k.owner, chat.id).members.map { it.deviceId })
    }

    @Test fun ownerCannotLeaveWithoutTransfer() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val r = leaveChat(k.owner, chat.id)
        assertEquals(HttpStatusCode.Conflict, r.status); assertEquals("owner_must_transfer", r.body<ErrorResponse>().error)
        // Transfer: only the owner, only to a member.
        suspend fun transfer(d: RegisterResponse, to: String) =
            client.post("/v1/chats/${chat.id}/owner") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(TransferOwnerRequest(to)) }.status
        assertEquals(HttpStatusCode.Forbidden, transfer(k.member, k.member.deviceId))
        assertEquals(HttpStatusCode.BadRequest, transfer(k.owner, k.outsider.deviceId))
        assertEquals(HttpStatusCode.OK, transfer(k.owner, k.member.deviceId))
        assertEquals(k.member.deviceId, sync(k.member, chat.id).ownerId)
        assertEquals(HttpStatusCode.NoContent, leaveChat(k.owner, chat.id).status)
        assertEquals(HttpStatusCode.NotFound, syncResponse(k.owner, chat.id).status)
        // The new (and only) member is the owner, so it cannot slip away either; the last-member delete happens via the space.
        assertEquals(HttpStatusCode.Conflict, leaveChat(k.member, chat.id).status)
    }

    @Test fun nonAdminCannotRemoveOthers() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        assertEquals(HttpStatusCode.Forbidden, leaveChat(k.member, chat.id, k.owner.deviceId).status)
        assertEquals(HttpStatusCode.Forbidden, leaveChat(k.owner, chat.id, k.member.deviceId).status) // even the chat owner
        assertEquals(2, sync(k.owner, chat.id).members.size)
        // The admin may, and it shows as an admin removal authored by the admin.
        assertEquals(HttpStatusCode.NoContent, leaveChat(k.admin, chat.id, k.member.deviceId).status)
        val s = sync(k.owner, chat.id)
        assertEquals(listOf(k.owner.deviceId), s.members.map { it.deviceId })
        val ev = s.messages.last().event as ChatEvent.MemberRemoved
        assertEquals(k.member.deviceId, ev.deviceId); assertTrue(ev.byAdmin)
        assertEquals(k.admin.deviceId, s.messages.last().author.deviceId)
        assertEquals(HttpStatusCode.NotFound, syncResponse(k.member, chat.id).status)
        // Removing a non-member: 404.
        assertEquals(HttpStatusCode.NotFound, leaveChat(k.admin, chat.id, k.outsider.deviceId).status)
    }

    @Test fun removedFromSpaceLosesChats() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        withEvents(k.admin) {
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${k.space.id}/members/${k.member.deviceId}") { bearerAuth(k.admin.token) }.status)
            assertIs<RelayEvent.SpaceChanged>(next())
            assertEquals(chat.id, assertIs<RelayEvent.ChatChanged>(next()).chatId) // the admin is told too
        }
        assertEquals(HttpStatusCode.NotFound, syncResponse(k.member, chat.id).status)
        assertEquals(HttpStatusCode.NotFound, post(k.member, chat.id, "still here?").status)
        assertEquals(listOf(k.owner.deviceId), sync(k.owner, chat.id).members.map { it.deviceId })
        // An owner removed from the space hands the chat to the longest-standing member.
        val chat2 = newChat(k.owner, k.space.id, "Two"); addMember(k.owner, chat2.id, k.outsider)
        client.delete("/v1/spaces/${k.space.id}/members/${k.owner.deviceId}") { bearerAuth(k.admin.token) }
        assertEquals(k.outsider.deviceId, sync(k.outsider, chat2.id).ownerId)
        // The last member leaving the space deletes the chat, and the admin hears of it.
        withEvents(k.admin) {
            client.delete("/v1/spaces/${k.space.id}/members/${k.outsider.deviceId}") { bearerAuth(k.outsider.token) }
            val events = List(2) { next() }
            assertTrue(RelayEvent.ChatDeleted(chat2.id) in events, events.toString())
        }
        assertNull(store.chat(chat2.id))
    }

    @Test fun deletingSpaceDeletesChatsAndTellsAdminAndMembers() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        withEvents(k.owner) {
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${k.space.id}") { bearerAuth(k.admin.token) }.status)
            val events = List(2) { next() }
            assertTrue(RelayEvent.ChatDeleted(chat.id) in events, events.toString())
        }
        assertNull(store.chat(chat.id))
        assertEquals(HttpStatusCode.NotFound, syncResponse(k.owner, chat.id).status)
    }

    @Test fun adminReadsAndModeratesWithoutMembership() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val m = say(k.member, chat.id, "hello")
        assertEquals(listOf("hello"), sync(k.admin, chat.id).messages.mapNotNull { it.text })
        assertEquals(HttpStatusCode.NoContent, delMessage(k.admin, chat.id, m.id).status)
        val gone = sync(k.owner, chat.id).messages.single { it.id == m.id }
        assertTrue(gone.deleted); assertTrue(gone.deletedByAdmin); assertNull(gone.text)
        // Rename, and delete the chat.
        val r = client.patch("/v1/chats/${chat.id}") { bearerAuth(k.admin.token); contentType(ContentType.Application.Json); setBody(RenameChatRequest("Renamed")) }
        assertEquals("Renamed", r.body<ChatSummary>().name)
        assertEquals(HttpStatusCode.Forbidden, client.patch("/v1/chats/${chat.id}") {
            bearerAuth(k.member.token); contentType(ContentType.Application.Json); setBody(RenameChatRequest("Mine"))
        }.status)
        assertTrue(sync(k.owner, chat.id).messages.any { it.event == ChatEvent.Renamed("Renamed") })
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/chats/${chat.id}") { bearerAuth(k.admin.token) }.status)
        assertNull(store.chat(chat.id))
    }

    @Test fun adminCannotEditOthersText() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        val m = say(k.owner, chat.id, "mine")
        assertEquals(HttpStatusCode.Forbidden, editMessage(k.admin, chat.id, m.id, "changed").status)
        assertEquals("not_author", editMessage(k.admin, chat.id, m.id, "changed").body<ErrorResponse>().error)
        assertEquals("mine", sync(k.owner, chat.id).messages.single { it.id == m.id }.text)
    }

    @Test fun adminMustJoinToPost() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        val r = post(k.admin, chat.id, "hi")
        assertEquals(HttpStatusCode.Forbidden, r.status); assertEquals("not_member", r.body<ErrorResponse>().error)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/chats/${chat.id}/read") {
            bearerAuth(k.admin.token); contentType(ContentType.Application.Json); setBody(ReadRequest(1))
        }.status)
        // Only the admin joins directly.
        assertEquals(HttpStatusCode.Forbidden, act(k.owner, "/v1/chats/${chat.id}/join"))
        assertEquals(HttpStatusCode.NotFound, act(k.member, "/v1/chats/${chat.id}/join"))
        val joined = client.post("/v1/chats/${chat.id}/join") { bearerAuth(k.admin.token) }
        assertEquals(HttpStatusCode.OK, joined.status); assertTrue(joined.body<ChatSummary>().member)
        assertEquals(HttpStatusCode.Conflict, act(k.admin, "/v1/chats/${chat.id}/join"))
        assertEquals(HttpStatusCode.Created, post(k.admin, chat.id, "hi").status)
        // An admin deleting their own message is not a moderation removal.
        val mine = say(k.admin, chat.id, "oops")
        delMessage(k.admin, chat.id, mine.id)
        assertFalse(sync(k.admin, chat.id).messages.single { it.id == mine.id }.deletedByAdmin)
    }

    @Test fun onlyAuthorEdits() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val m = say(k.member, chat.id, "first")
        assertEquals(HttpStatusCode.Forbidden, editMessage(k.owner, chat.id, m.id, "no").status) // even the chat owner
        assertEquals(HttpStatusCode.NotFound, editMessage(k.outsider, chat.id, m.id, "no").status)
        clock.now += 1000
        val edited = editMessage(k.member, chat.id, m.id, "second")
        assertEquals(HttpStatusCode.OK, edited.status)
        val body = edited.body<ChatMessage>()
        assertEquals("second", body.text); assertEquals(clock.now, body.editedAtEpochMs); assertTrue(body.rev > m.rev)
        // A message id of another chat is not found here.
        val other = newChat(k.owner, k.space.id, "Other")
        assertEquals(HttpStatusCode.NotFound, editMessage(k.owner, other.id, m.id, "x").status)
        // Deleted messages cannot be edited.
        delMessage(k.member, chat.id, m.id)
        assertEquals(HttpStatusCode.Conflict, editMessage(k.member, chat.id, m.id, "back").status)
    }

    @Test fun deleteByAuthorOrAdmin() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val a = say(k.member, chat.id, "a"); val b = say(k.member, chat.id, "b")
        assertEquals(HttpStatusCode.Forbidden, delMessage(k.owner, chat.id, a.id).status) // chat owner is not the space admin
        assertEquals(HttpStatusCode.NotFound, delMessage(k.outsider, chat.id, a.id).status)
        assertEquals(HttpStatusCode.NoContent, delMessage(k.member, chat.id, a.id).status)
        assertEquals(HttpStatusCode.NoContent, delMessage(k.admin, chat.id, b.id).status)
        val msgs = sync(k.owner, chat.id).messages
        val ma = msgs.single { it.id == a.id }; val mb = msgs.single { it.id == b.id }
        assertTrue(ma.deleted && !ma.deletedByAdmin && ma.text == null)
        assertTrue(mb.deleted && mb.deletedByAdmin && mb.text == null)
        assertEquals(HttpStatusCode.Conflict, delMessage(k.member, chat.id, a.id).status)
        // Deleted messages do not preview in the list: the newest live entry is the member's join.
        assertIs<ChatEvent.MemberJoined>(chats(k.owner, k.space.id).single().lastMessage?.event)
    }

    @Test fun chatDeleteOwnerOrAdminOnly() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/v1/chats/${chat.id}") { bearerAuth(k.member.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/chats/${chat.id}") { bearerAuth(k.outsider.token) }.status)
        assertNotNull(store.chat(chat.id))
        withEvents(k.member) {
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/chats/${chat.id}") { bearerAuth(k.owner.token) }.status)
            assertEquals(RelayEvent.ChatDeleted(chat.id), next())
        }
        assertNull(store.chat(chat.id))
        assertEquals(HttpStatusCode.NotFound, syncResponse(k.member, chat.id).status)
    }

    @Test fun longUnicodeTextRoundTrips() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        val text = "héllo 日本語 😀 ".repeat(100) + "😀".repeat(3000) // 4000 code points at most? see below
        val exactly4000 = "😀".repeat(4000)
        val m = say(k.owner, chat.id, exactly4000)
        assertEquals(exactly4000, m.text)
        assertEquals(exactly4000, sync(k.owner, chat.id).messages.single { it.id == m.id }.text)
        val mixed = "ñ日本😀\n  tab\tend"
        assertEquals(mixed, say(k.owner, chat.id, mixed).text)
    }

    @Test fun over4000CharsRejected() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        val r = post(k.owner, chat.id, "x".repeat(4001))
        assertEquals(HttpStatusCode.BadRequest, r.status); assertEquals("too_long", r.body<ErrorResponse>().error)
        assertEquals(HttpStatusCode.Created, post(k.owner, chat.id, "x".repeat(4000)).status)
        val m = say(k.owner, chat.id, "short")
        assertEquals("too_long", editMessage(k.owner, chat.id, m.id, "y".repeat(4001)).body<ErrorResponse>().error)
        assertEquals(HttpStatusCode.BadRequest, post(k.owner, chat.id, "   ").status)
        assertEquals(2, sync(k.owner, chat.id).messages.size)
    }

    @Test fun eventsGoToMembersAndAdminOnly() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        withEvents(k.admin) {
            val admin = this
            withEvents(k.member) {
                val member = this
                withEvents(k.outsider) {
                    val outsider = this
                    val m = say(k.owner, chat.id, "hi")
                    // A space-wide event afterwards proves nothing chat-related was queued ahead of it for the outsider.
                    client.patch("/v1/spaces/${k.space.id}") {
                        bearerAuth(k.admin.token); contentType(ContentType.Application.Json); setBody(RenameSpaceRequest("New"))
                    }
                    assertEquals(RelayEvent.ChatChanged(chat.id, m.rev), admin.next())
                    assertEquals(RelayEvent.ChatChanged(chat.id, m.rev), member.next())
                    assertEquals(RelayEvent.SpaceChanged(k.space.id), outsider.next())
                }
            }
        }
    }

    @Test fun pushPingsOtherMembers() { val push = RecordingPush(); relayTest(push = push) {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        setPushToken(k.admin, "tok-admin"); setPushToken(k.owner, "tok-owner"); setPushToken(k.member, "tok-member"); setPushToken(k.outsider, "tok-out")
        say(k.member, chat.id, "from member")
        say(k.owner, chat.id, "from owner")
        withTimeout(5_000) { while (push.pinged.size < 2) delay(20) }
        // Each message rang only the other member; never the author, the non-member admin or an outsider.
        assertEquals(listOf("tok-member", "tok-owner"), push.pinged.sorted())
    } }

    @Test fun syncPagesLargeBacklog() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        repeat(450) { store.postMessage(chat.id, k.owner.deviceId, "m$it", clock.now) }
        var since = 0L; val seen = mutableListOf<ChatMessage>(); var pages = 0
        while (true) {
            val s = sync(k.owner, chat.id, since); pages++
            seen += s.messages
            if (!s.hasMore) break
            since = s.messages.last().rev
        }
        assertEquals(450, seen.size); assertEquals(3, pages)
        assertEquals(seen.map { it.rev }, seen.map { it.rev }.sorted())
        assertEquals(seen.size, seen.map { it.id }.toSet().size)
        // Caught up: nothing newer.
        assertEquals(emptyList(), sync(k.owner, chat.id, seen.last().rev).messages)
    }

    @Test fun guestGets403OnChatRoutes() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        val (_, guest) = guestOf(k.owner)
        for (r in listOf(
            client.get("/v1/spaces/${k.space.id}/chats") { bearerAuth(guest.token) },
            client.get("/v1/chat-invites") { bearerAuth(guest.token) },
            syncResponse(guest, chat.id),
            post(guest, chat.id, "hi"),
        )) {
            assertEquals(HttpStatusCode.Forbidden, r.status); assertEquals("guest", r.body<ErrorResponse>().error)
        }
    }

    @Test fun selfTransferIsRejected() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        val r = client.post("/v1/chats/${chat.id}/owner") { bearerAuth(k.owner.token); contentType(ContentType.Application.Json); setBody(TransferOwnerRequest(k.owner.deviceId)) }
        assertEquals(HttpStatusCode.BadRequest, r.status); assertEquals("already_owner", r.body<ErrorResponse>().error)
    }

    @Test fun messageRoutesAreRateLimited() = relayTest(limits = RelayLimits(chatMessagesPerMinute = 3)) {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val m = say(k.owner, chat.id, "1")                                    // 1
        assertEquals(HttpStatusCode.OK, editMessage(k.owner, chat.id, m.id, "2").status) // 2
        assertEquals(HttpStatusCode.NoContent, delMessage(k.owner, chat.id, m.id).status) // 3
        assertEquals(HttpStatusCode.TooManyRequests, post(k.owner, chat.id, "4").status)
        assertEquals(HttpStatusCode.TooManyRequests, editMessage(k.owner, chat.id, m.id, "5").status)
        assertEquals(HttpStatusCode.TooManyRequests, delMessage(k.owner, chat.id, m.id).status)
        // The limit is per device, and reading is not limited.
        assertEquals(HttpStatusCode.Created, post(k.member, chat.id, "x").status)
        assertEquals(HttpStatusCode.OK, syncResponse(k.owner, chat.id).status)
    }

    @Test fun chatDeleteTellsPendingInvitees() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        invite(k.member, chat.id, k.outsider.deviceId) // pending: the owner owes an approval
        withEvents(k.outsider) {
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/chats/${chat.id}") { bearerAuth(k.owner.token) }.status)
            assertEquals(RelayEvent.ChatInvitesChanged, next())
        }
        assertEquals(emptyList(), invites(k.outsider))
    }

    @Test fun inviteeLeavingSpaceTellsOwner() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        invite(k.member, chat.id, k.outsider.deviceId) // the outsider is not in the chat
        assertEquals(1, invites(k.owner).size)
        withEvents(k.owner) {
            client.delete("/v1/spaces/${k.space.id}/members/${k.outsider.deviceId}") { bearerAuth(k.outsider.token) }
            val events = List(2) { next() }
            assertTrue(RelayEvent.ChatInvitesChanged in events, events.toString())
        }
        assertEquals(emptyList(), invites(k.owner))
    }

    @Test fun inviterLeavingSpaceTellsApprovedInvitee() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val inv = invite(k.member, chat.id, k.outsider.deviceId).body<ChatInvite>()
        act(k.owner, "/v1/chat-invites/${inv.id}/approve")
        assertEquals(1, invites(k.outsider).size)
        withEvents(k.outsider) {
            client.delete("/v1/spaces/${k.space.id}/members/${k.member.deviceId}") { bearerAuth(k.admin.token) }
            val events = List(2) { next() }
            assertTrue(RelayEvent.ChatInvitesChanged in events, events.toString())
        }
        assertEquals(emptyList(), invites(k.outsider))
    }

    @Test fun adminRemovalTellsInviteesOfRemovedMember() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val inv = invite(k.member, chat.id, k.outsider.deviceId).body<ChatInvite>()
        act(k.owner, "/v1/chat-invites/${inv.id}/approve")
        withEvents(k.outsider) {
            assertEquals(HttpStatusCode.NoContent, leaveChat(k.admin, chat.id, k.member.deviceId).status)
            assertEquals(RelayEvent.ChatInvitesChanged, next())
        }
        assertEquals(emptyList(), invites(k.outsider))
    }

    @Test fun removedMemberGetsChatDeleted() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val c2 = newChat(k.owner, k.space.id, "Two"); addMember(k.owner, c2.id, k.member)
        withEvents(k.member) {
            // Admin removes the member from one chat.
            leaveChat(k.admin, chat.id, k.member.deviceId)
            assertEquals(RelayEvent.ChatDeleted(chat.id), next())
            // Admin removes the member from the space: the other chat goes too.
            client.delete("/v1/spaces/${k.space.id}/members/${k.member.deviceId}") { bearerAuth(k.admin.token) }
            val events = List(2) { next() }
            assertTrue(RelayEvent.ChatDeleted(c2.id) in events, events.toString())
        }
    }

    @Test fun listShowsLastMessageAndReadRev() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id)
        val m = say(k.owner, chat.id, "last")
        val s = chats(k.owner, k.space.id).single()
        assertEquals("last", s.lastMessage?.text); assertEquals(0, s.readRev); assertEquals(m.rev, s.rev)
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/chats/${chat.id}/read") {
            bearerAuth(k.owner.token); contentType(ContentType.Application.Json); setBody(ReadRequest(m.rev))
        }.status)
        assertEquals(m.rev, chats(k.owner, k.space.id).single().readRev)
        delMessage(k.owner, chat.id, m.id)
        assertNull(chats(k.owner, k.space.id).single().lastMessage?.text)
    }

    // ---- Mentions and seen/read receipts ----

    private suspend fun RelayTestScope.postMentioning(d: RegisterResponse, chatId: String, text: String, vararg ids: String): HttpResponse =
        client.post("/v1/chats/$chatId/messages") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(PostMessageRequest(text, ids.toList())) }

    private suspend fun RelayTestScope.report(d: RegisterResponse, chatId: String, what: String, rev: Long): HttpResponse =
        client.post("/v1/chats/$chatId/$what") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(if (what == "seen") SeenRequest(rev) else ReadRequest(rev)) }

    @Test fun mentionsMustBeMembers() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        for (bad in listOf(k.outsider.deviceId, k.admin.deviceId, "nobody")) {
            val r = postMentioning(k.owner, chat.id, "hi", bad)
            assertEquals(HttpStatusCode.BadRequest, r.status, bad); assertEquals("invalid_mention", r.body<ErrorResponse>().error)
        }
        val ok = postMentioning(k.owner, chat.id, "hi @C", k.member.deviceId, k.member.deviceId)
        assertEquals(HttpStatusCode.Created, ok.status)
        val m = ok.body<ChatMessage>()
        assertEquals(listOf(k.member.deviceId), m.mentions)
        assertEquals(listOf(k.member.deviceId), sync(k.owner, chat.id).messages.single { it.id == m.id }.mentions)
        // Edits are checked the same way, and may change the mentions.
        val bad = client.patch("/v1/chats/${chat.id}/messages/${m.id}") {
            bearerAuth(k.owner.token); contentType(ContentType.Application.Json); setBody(EditMessageRequest("x", listOf(k.outsider.deviceId)))
        }
        assertEquals("invalid_mention", bad.body<ErrorResponse>().error)
        val edited = client.patch("/v1/chats/${chat.id}/messages/${m.id}") {
            bearerAuth(k.owner.token); contentType(ContentType.Application.Json); setBody(EditMessageRequest("hi", emptyList()))
        }.body<ChatMessage>()
        assertEquals(emptyList(), edited.mentions)
        // A mention of someone who left is refused: only current members count.
        leaveChat(k.member, chat.id)
        assertEquals("invalid_mention", postMentioning(k.owner, chat.id, "hi", k.member.deviceId).body<ErrorResponse>().error)
        // A deleted message keeps no mentions.
        val d = postMentioning(k.owner, chat.id, "bye", k.owner.deviceId).body<ChatMessage>()
        delMessage(k.owner, chat.id, d.id)
        assertEquals(emptyList(), sync(k.owner, chat.id).messages.single { it.id == d.id }.mentions)
    }

    @Test fun mentionedDeviceAlwaysPinged() { val push = RecordingPush(); relayTest(push = push) {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member); addMember(k.owner, chat.id, k.outsider)
        setPushToken(k.owner, "tok-owner"); setPushToken(k.member, "tok-member"); setPushToken(k.outsider, "tok-d")
        withEvents(k.member) {
            val member = this
            withEvents(k.outsider) {
                val other = this
                val m = postMentioning(k.owner, chat.id, "hi", k.member.deviceId).body<ChatMessage>()
                assertEquals(RelayEvent.ChatChanged(chat.id, m.rev, mentioned = true), member.next())
                assertEquals(RelayEvent.ChatChanged(chat.id, m.rev), other.next())
            }
        }
        withTimeout(5_000) { while (push.pinged.size < 2) delay(20) }
        assertTrue("tok-member" in push.pinged)
        assertFalse("tok-owner" in push.pinged) // never the author
    } }

    @Test fun seenAndReadReceiptsInSync() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val m = say(k.owner, chat.id, "hi")
        clock.now = 5_000
        assertEquals(listOf(0L, 0L), sync(k.owner, chat.id).receipts.map { it.seenRev })
        val first = sync(k.owner, chat.id).receipts.single { it.deviceId == k.member.deviceId }
        assertEquals(0L, first.readRev); assertNull(first.seenAtEpochMs); assertNull(first.readAtEpochMs)
        withEvents(k.owner) {
            assertEquals(HttpStatusCode.NoContent, report(k.member, chat.id, "seen", m.rev).status)
            assertEquals(RelayEvent.ChatChanged(chat.id, m.rev, receipts = listOf(Receipt(k.member.deviceId, m.rev, 0, 5_000, null))), next())
            clock.now = 9_000
            assertEquals(HttpStatusCode.NoContent, report(k.member, chat.id, "read", m.rev).status)
            assertEquals(RelayEvent.ChatChanged(chat.id, m.rev, receipts = listOf(Receipt(k.member.deviceId, m.rev, m.rev, 5_000, 9_000))), next())
            // Repeating changes nothing, so it rings nobody.
            assertEquals(HttpStatusCode.NoContent, report(k.member, chat.id, "read", m.rev).status)
            assertEquals(HttpStatusCode.NoContent, report(k.member, chat.id, "seen", 1).status)
        }
        val r = sync(k.owner, chat.id, m.rev).receipts.single { it.deviceId == k.member.deviceId }
        assertEquals(Receipt(k.member.deviceId, m.rev, m.rev, 5_000, 9_000), r)
        // Never beyond the chat's rev; read counts as seen.
        report(k.owner, chat.id, "read", 99)
        val o = sync(k.member, chat.id).receipts.single { it.deviceId == k.owner.deviceId }
        assertEquals(sync(k.owner, chat.id).rev, o.readRev); assertEquals(o.readRev, o.seenRev)
        assertEquals(HttpStatusCode.NotFound, report(k.outsider, chat.id, "seen", 1).status)
    }

    @Test fun adminNonMemberNeverInReceipts() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val m = say(k.owner, chat.id, "hi")
        val r = report(k.admin, chat.id, "seen", m.rev)
        assertEquals(HttpStatusCode.Forbidden, r.status); assertEquals("not_member", r.body<ErrorResponse>().error)
        assertEquals(HttpStatusCode.Forbidden, report(k.admin, chat.id, "read", m.rev).status)
        sync(k.admin, chat.id) // reading the chat as admin records nothing
        assertEquals(setOf(k.owner.deviceId, k.member.deviceId), sync(k.owner, chat.id).receipts.map { it.deviceId }.toSet())
        // Once the admin joins, they are a member and show.
        assertEquals(HttpStatusCode.OK, client.post("/v1/chats/${chat.id}/join") { bearerAuth(k.admin.token) }.status)
        assertEquals(HttpStatusCode.NoContent, report(k.admin, chat.id, "seen", m.rev).status)
        assertTrue(k.admin.deviceId in sync(k.owner, chat.id).receipts.map { it.deviceId })
    }

    private suspend fun RelayTestScope.msgReceipts(d: RegisterResponse, chatId: String, msgId: String): HttpResponse =
        client.get("/v1/chats/$chatId/messages/$msgId/receipts") { bearerAuth(d.token) }

    @Test fun editedMessageKeepsItsReceipts() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val m = say(k.owner, chat.id, "typo")
        clock.now = 1_000; report(k.member, chat.id, "read", m.rev)
        val edited = editMessage(k.owner, chat.id, m.id, "fixed").body<ChatMessage>()
        assertTrue(edited.rev > m.rev); assertEquals(m.rev, edited.createdRev)
        assertEquals(m.rev, sync(k.owner, chat.id, m.rev).messages.single { it.id == m.id }.createdRev)
        val r = msgReceipts(k.owner, chat.id, m.id).body<List<MessageReceipt>>().single { it.deviceId == k.member.deviceId }
        assertEquals(MessageReceipt(k.member.deviceId, 1_000, 1_000), r) // the edit did not take the read away
        // A message id of another chat is not found here.
        val other = newChat(k.owner, k.space.id, "Other"); val o = say(k.owner, other.id, "elsewhere")
        assertEquals(HttpStatusCode.NotFound, msgReceipts(k.owner, chat.id, o.id).status)
    }

    @Test fun infoTimesArePerMessage() = relayTest {
        val k = cast(); val chat = newChat(k.owner, k.space.id); addMember(k.owner, chat.id, k.member)
        val m1 = say(k.owner, chat.id, "one"); val m2 = say(k.owner, chat.id, "two")
        clock.now = 1_000; report(k.member, chat.id, "seen", m1.rev)
        clock.now = 2_000; report(k.member, chat.id, "seen", m2.rev)
        clock.now = 3_000; report(k.member, chat.id, "read", m1.rev)
        clock.now = 4_000; report(k.member, chat.id, "read", m2.rev)
        clock.now = 5_000; report(k.owner, chat.id, "read", m2.rev) // read counts as seen
        fun List<MessageReceipt>.of(d: RegisterResponse) = single { it.deviceId == d.deviceId }
        val first = msgReceipts(k.owner, chat.id, m1.id).body<List<MessageReceipt>>()
        assertEquals(MessageReceipt(k.member.deviceId, 1_000, 3_000), first.of(k.member)) // not the latest times
        val second = msgReceipts(k.owner, chat.id, m2.id).body<List<MessageReceipt>>()
        assertEquals(MessageReceipt(k.member.deviceId, 2_000, 4_000), second.of(k.member))
        assertEquals(MessageReceipt(k.owner.deviceId, 5_000, 5_000), second.of(k.owner))
        // Members and the space admin only; the admin is never listed.
        assertEquals(2, msgReceipts(k.admin, chat.id, m1.id).body<List<MessageReceipt>>().size)
        assertEquals(HttpStatusCode.NotFound, msgReceipts(k.outsider, chat.id, m1.id).status)
        assertEquals(HttpStatusCode.NotFound, msgReceipts(k.owner, chat.id, "nope").status)
        // The marks go with the chat.
        assertTrue(store.rowCount("chat_receipt_marks") > 0)
        client.delete("/v1/chats/${chat.id}") { bearerAuth(k.owner.token) }
        assertEquals(0, store.rowCount("chat_receipt_marks"))
    }
}
