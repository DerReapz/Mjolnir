package app.mjolnir.server

import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.ChatSync
import app.mjolnir.protocol.CreateChatRequest
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.EditMessageRequest
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.InviteRequest
import app.mjolnir.protocol.InviteState
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.PostMessageRequest
import app.mjolnir.protocol.ReadRequest
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.RenameChatRequest
import app.mjolnir.protocol.SeenRequest
import app.mjolnir.protocol.TransferOwnerRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

/**
 * After [deviceId] left [spaceId] (itself or removed by the admin): tells everyone who could see a changed or deleted chat,
 * plus the space admin, and every party whose invites went with it.
 */
fun emitLeftSpace(deps: RelayDeps, adminId: String, deviceId: String, chats: LeftSpaceResult) {
    chats.changed.forEach { (chatId, audience) ->
        val rev = deps.store.chat(chatId)?.rev ?: return@forEach
        // The leaver's client drops the chat at once; everyone else just re-syncs.
        (audience + adminId).toSet().forEach {
            deps.hub.emit(it, if (it == deviceId) RelayEvent.ChatDeleted(chatId) else RelayEvent.ChatChanged(chatId, rev))
        }
    }
    chats.deleted.forEach { (chatId, audience) ->
        (audience + adminId).toSet().forEach { deps.hub.emit(it, RelayEvent.ChatDeleted(chatId)) }
    }
    chats.invites.forEach { deps.hub.emit(it, RelayEvent.ChatInvitesChanged) }
}

private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String) = respond(status, ErrorResponse(code))

private fun text(raw: String): String? =
    raw.takeIf { it.codePointCount(0, it.length) <= Limits.MAX_CHAT_TEXT_LENGTH }

fun Route.chatRoutes(deps: RelayDeps) {
    val store = deps.store

    /** The caller's view of a chat: it exists for them only as a member or as the space admin. */
    class Access(val chat: ChatRow, val me: String, val isAdmin: Boolean, val isMember: Boolean)

    fun me(call: ApplicationCall) = call.principal<DevicePrincipal>()!!.deviceId

    fun adminOf(chat: ChatRow): String? = store.space(chat.spaceId)?.adminId

    /** Everyone who sees [chat]: its members and the space admin. */
    fun audience(chat: ChatRow): Set<String> = (store.chatMembers(chat.id).map { it.id } + listOfNotNull(adminOf(chat))).toSet()

    /** 404 unless the caller is a member of the chat or the admin of its space (so existence never leaks). */
    suspend fun ApplicationCall.access(chatId: String = parameters["id"]!!): Access? {
        val me = me(this)
        val chat = store.chat(chatId)
        val isAdmin = chat != null && adminOf(chat) == me
        val isMember = chat != null && store.isChatMember(chat.id, me)
        if (chat == null || !(isAdmin || isMember)) {
            error(HttpStatusCode.NotFound, "not_found")
            return null
        }
        return Access(chat, me, isAdmin, isMember)
    }

    fun peer(id: String): Peer = store.device(id)?.toPeer() ?: Peer(id, "Unknown device", DeviceKind.PC)

    fun ChatMessageRow.toWire() = ChatMessage(
        id, chatId, peer(authorId), rev, sentAtMs, editedAtMs, deleted, deletedByAdmin, if (deleted) null else text, event, mentions, createdRev,
    )

    fun summary(chat: ChatRow, me: String): ChatSummary = ChatSummary(
        chat.id, chat.spaceId, store.space(chat.spaceId)?.name.orEmpty(), chat.name, chat.ownerId, chat.rev,
        store.readRev(chat.id, me), store.isChatMember(chat.id, me), store.lastChatMessage(chat.id)?.toWire(),
    )

    /** Answers with the chat's current summary, or 404 when it was deleted meanwhile. */
    suspend fun ApplicationCall.respondChat(chatId: String, me: String) {
        val chat = store.chat(chatId) ?: return error(HttpStatusCode.NotFound, "not_found")
        respond(summary(chat, me))
    }

    fun inviteView(row: ChatInviteRow): ChatInvite? {
        val chat = store.chat(row.chatId) ?: return null
        return ChatInvite(row.id, chat.id, chat.name, store.space(chat.spaceId)?.name.orEmpty(), peer(row.inviteeId), peer(row.inviterId), row.state)
    }

    /** Tells [to] that a chat changed; the rev is read after the change. */
    fun emitChanged(chatId: String, to: Collection<String>) {
        val rev = store.chat(chatId)?.rev ?: return
        to.toSet().forEach { deps.hub.emit(it, RelayEvent.ChatChanged(chatId, rev)) }
    }

    /**
     * A message change: event to members and admin, doorbell to the members other than [actor]. The event to a member in
     * [mentioned] says so, and its doorbell is rung whatever else is going on.
     */
    fun fanOut(chat: ChatRow, actor: String, mentioned: Set<String> = emptySet()) {
        val members = store.chatMembers(chat.id).map { it.id }
        val rev = store.chat(chat.id)?.rev ?: return
        val told = mentioned - actor
        audience(chat).forEach { deps.hub.emit(it, RelayEvent.ChatChanged(chat.id, rev, mentioned = it in told)) }
        members.filter { it != actor }.forEach { deps.pingDevice(it) }
    }

    /** The mentions as a list of distinct device ids, or null unless every one is a current member of [chat]. */
    fun validMentions(chat: ChatRow, ids: List<String>): List<String>? =
        ids.distinct().takeIf { list -> list.all { store.isChatMember(chat.id, it) } }

    /** Receipts changed, not the chat: a rev-less event for everyone who shows them, other than the reporter. */
    fun emitReceipts(chat: ChatRow, actor: String) {
        val rev = store.chat(chat.id)?.rev ?: return
        val changed = store.receipts(chat.id).filter { it.deviceId == actor }
        (audience(chat) - actor).forEach { deps.hub.emit(it, RelayEvent.ChatChanged(chat.id, rev, receipts = changed)) }
    }

    fun emitInvites(chat: ChatRow, vararg others: String) {
        (listOf(chat.ownerId) + others).toSet().forEach { deps.hub.emit(it, RelayEvent.ChatInvitesChanged) }
    }

    fun emitInvites(parties: Collection<String>) = parties.toSet().forEach { deps.hub.emit(it, RelayEvent.ChatInvitesChanged) }

    /** The invite and its chat, for a caller that is the chat's member, its space admin or the invitee; else 404. */
    suspend fun ApplicationCall.inviteAccess(): Triple<ChatInviteRow, ChatRow, String>? {
        val me = me(this)
        val inv = store.chatInvite(parameters["id"]!!)
        val chat = inv?.let { store.chat(it.chatId) }
        if (inv == null || chat == null ||
            !(inv.inviteeId == me || store.isChatMember(chat.id, me) || adminOf(chat) == me)
        ) {
            error(HttpStatusCode.NotFound, "not_found")
            return null
        }
        return Triple(inv, chat, me)
    }

    authenticate("device") {
        get("/v1/spaces/{id}/chats") {
            val me = me(call)
            val space = store.space(call.parameters["id"]!!)
            if (space == null || store.members(space.id).none { it.id == me }) return@get call.error(HttpStatusCode.NotFound, "not_found")
            call.respond(store.chatsOf(space.id, me, space.adminId == me).map { summary(it, me) })
        }

        post("/v1/spaces/{id}/chats") {
            val me = me(call)
            val space = store.space(call.parameters["id"]!!)
            if (space == null || store.members(space.id).none { it.id == me }) return@post call.error(HttpStatusCode.NotFound, "not_found")
            val name = validSpaceName(call.receive<CreateChatRequest>().name)
                ?: return@post call.error(HttpStatusCode.BadRequest, "invalid_name")
            val chat = store.createChat(space.id, me, name, deps.clock.nowMs())
                ?: return@post call.error(HttpStatusCode.NotFound, "not_found")
            emitChanged(chat.id, listOf(me, space.adminId))
            call.respond(HttpStatusCode.Created, summary(chat, me))
        }

        patch("/v1/chats/{id}") {
            val a = call.access() ?: return@patch
            if (a.chat.ownerId != a.me && !a.isAdmin) return@patch call.error(HttpStatusCode.Forbidden, "not_owner")
            val name = validSpaceName(call.receive<RenameChatRequest>().name)
                ?: return@patch call.error(HttpStatusCode.BadRequest, "invalid_name")
            if (!store.renameChat(a.chat.id, name, a.me, deps.clock.nowMs())) return@patch call.error(HttpStatusCode.NotFound, "not_found")
            emitChanged(a.chat.id, audience(a.chat))
            call.respondChat(a.chat.id, a.me)
        }

        delete("/v1/chats/{id}") {
            val a = call.access() ?: return@delete
            if (a.chat.ownerId != a.me && !a.isAdmin) return@delete call.error(HttpStatusCode.Forbidden, "not_owner")
            val before = audience(a.chat)
            val parties = store.inviteParties(a.chat.id)
            store.deleteChat(a.chat.id)
            before.forEach { deps.hub.emit(it, RelayEvent.ChatDeleted(a.chat.id)) }
            emitInvites(parties)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- Invites ----

        post("/v1/chats/{id}/invites") {
            val a = call.access() ?: return@post
            if (!a.isMember) return@post call.error(HttpStatusCode.Forbidden, "not_member")
            val invitee = call.receive<InviteRequest>().deviceId
            if (store.members(a.chat.spaceId).none { it.id == invitee }) return@post call.error(HttpStatusCode.BadRequest, "not_in_space")
            if (store.isChatMember(a.chat.id, invitee)) return@post call.error(HttpStatusCode.Conflict, "already_member")
            val row = store.createInvite(a.chat.id, invitee, a.me, deps.clock.nowMs())
                ?: return@post call.error(HttpStatusCode.Conflict, "already_invited")
            // While the owner still has to approve, the invitee does not hear of it.
            if (row.state == InviteState.PENDING_APPROVAL) emitInvites(a.chat, a.me) else emitInvites(a.chat, a.me, invitee)
            call.respond(HttpStatusCode.Created, inviteView(row) ?: return@post call.error(HttpStatusCode.NotFound, "not_found"))
        }

        get("/v1/chat-invites") {
            val me = me(call)
            call.respond(store.invitesFor(me, deps.clock.nowMs()).mapNotNull(::inviteView))
        }

        for ((action, to) in listOf("approve" to InviteState.APPROVED, "deny" to InviteState.DENIED)) {
            post("/v1/chat-invites/{id}/$action") {
                val (inv, chat, me) = call.inviteAccess() ?: return@post
                if (chat.ownerId != me) return@post call.error(HttpStatusCode.Forbidden, "not_owner")
                if (!store.setInviteState(inv.id, InviteState.PENDING_APPROVAL, to, deps.clock.nowMs())) {
                    return@post call.error(HttpStatusCode.Conflict, "invite_unavailable")
                }
                if (to == InviteState.APPROVED) emitInvites(chat, inv.inviterId, inv.inviteeId) else emitInvites(chat, inv.inviterId)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        post("/v1/chat-invites/{id}/decline") {
            val (inv, chat, me) = call.inviteAccess() ?: return@post
            if (inv.inviteeId != me) return@post call.error(HttpStatusCode.Forbidden, "not_invitee")
            if (!store.setInviteState(inv.id, InviteState.APPROVED, InviteState.DECLINED, deps.clock.nowMs())) {
                return@post call.error(HttpStatusCode.Conflict, "invite_unavailable")
            }
            emitInvites(chat, inv.inviterId, inv.inviteeId)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/v1/chat-invites/{id}/join") {
            val (inv, chat, me) = call.inviteAccess() ?: return@post
            if (inv.inviteeId != me) return@post call.error(HttpStatusCode.Forbidden, "not_invitee")
            if (!store.joinViaInvite(inv.id, me, deps.clock.nowMs())) return@post call.error(HttpStatusCode.Conflict, "invite_unavailable")
            emitInvites(chat, inv.inviterId, inv.inviteeId)
            emitChanged(chat.id, audience(chat))
            call.respondChat(chat.id, me)
        }

        // ---- Membership ----

        post("/v1/chats/{id}/owner") {
            val a = call.access() ?: return@post
            if (a.chat.ownerId != a.me) return@post call.error(HttpStatusCode.Forbidden, "not_owner")
            val target = call.receive<TransferOwnerRequest>().deviceId
            if (target == a.me) return@post call.error(HttpStatusCode.BadRequest, "already_owner")
            if (!store.isChatMember(a.chat.id, target) ||
                !store.transferChatOwner(a.chat.id, a.me, target, deps.clock.nowMs())
            ) return@post call.error(HttpStatusCode.BadRequest, "not_member")
            emitChanged(a.chat.id, audience(a.chat))
            call.respondChat(a.chat.id, a.me)
        }

        delete("/v1/chats/{id}/members/{deviceId}") {
            val a = call.access() ?: return@delete
            val target = call.parameters["deviceId"]!!
            val self = target == a.me
            // Removing someone else is moderation: the relay checks the space admin here, the store trusts it.
            if (!self && !a.isAdmin) return@delete call.error(HttpStatusCode.Forbidden, "not_admin")
            val before = audience(a.chat) + target
            val parties = store.inviteParties(a.chat.id, target, asInviter = !self)
            when (store.removeChatMember(a.chat.id, target, byAdmin = !self, nowMs = deps.clock.nowMs(), actorId = a.me)) {
                Store.RemoveResult.NOT_MEMBER -> return@delete call.error(HttpStatusCode.NotFound, "not_found")
                Store.RemoveResult.OWNER_MUST_TRANSFER -> return@delete call.error(HttpStatusCode.Conflict, "owner_must_transfer")
                Store.RemoveResult.REMOVED -> {
                    // A member removed by the admin drops the chat at once; the admin may still see it, so they just re-sync.
                    val dropped = if (self || target == adminOf(a.chat)) emptySet() else setOf(target)
                    emitChanged(a.chat.id, before - dropped)
                    dropped.forEach { deps.hub.emit(it, RelayEvent.ChatDeleted(a.chat.id)) }
                }
                Store.RemoveResult.LAST_MEMBER_DELETED -> before.forEach { deps.hub.emit(it, RelayEvent.ChatDeleted(a.chat.id)) }
            }
            emitInvites(parties)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/v1/chats/{id}/join") {
            val a = call.access() ?: return@post
            if (!a.isAdmin) return@post call.error(HttpStatusCode.Forbidden, "not_admin")
            if (a.isMember) return@post call.error(HttpStatusCode.Conflict, "already_member")
            if (!store.addChatMember(a.chat.id, a.me, deps.clock.nowMs())) return@post call.error(HttpStatusCode.NotFound, "not_found")
            emitChanged(a.chat.id, audience(a.chat))
            call.respondChat(a.chat.id, a.me)
        }

        // ---- Messages ----

        get("/v1/chats/{id}/sync") {
            val a = call.access() ?: return@get
            val since = call.request.queryParameters["sinceRev"]?.let { it.toLongOrNull() ?: return@get call.error(HttpStatusCode.BadRequest, "bad_request") } ?: 0L
            val page = store.syncPage(a.chat.id, since)
            val chat = store.chat(a.chat.id) ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
            call.respond(ChatSync(chat.id, page.chatRev, chat.ownerId, store.chatMembers(chat.id).map { it.toPeer() }, page.rows.map { it.toWire() }, page.hasMore, store.receipts(chat.id)))
        }

        rateLimit(ChatMessageLimit) {
            post("/v1/chats/{id}/messages") {
                val a = call.access() ?: return@post
                if (!a.isMember) return@post call.error(HttpStatusCode.Forbidden, "not_member")
                val req = call.receive<PostMessageRequest>()
                if (req.text.isBlank()) return@post call.error(HttpStatusCode.BadRequest, "empty")
                val body = text(req.text) ?: return@post call.error(HttpStatusCode.BadRequest, "too_long")
                val mentions = validMentions(a.chat, req.mentions) ?: return@post call.error(HttpStatusCode.BadRequest, "invalid_mention")
                val row = store.postMessage(a.chat.id, a.me, body, deps.clock.nowMs(), mentions)
                    ?: return@post call.error(HttpStatusCode.Forbidden, "not_member")
                fanOut(a.chat, a.me, mentions.toSet())
                call.respond(HttpStatusCode.Created, row.toWire())
            }

            patch("/v1/chats/{id}/messages/{msgId}") {
                val a = call.access() ?: return@patch
                val msg = store.message(a.chat.id, call.parameters["msgId"]!!) ?: return@patch call.error(HttpStatusCode.NotFound, "not_found")
                if (msg.authorId != a.me) return@patch call.error(HttpStatusCode.Forbidden, "not_author")
                val req = call.receive<EditMessageRequest>()
                if (req.text.isBlank()) return@patch call.error(HttpStatusCode.BadRequest, "empty")
                val body = text(req.text) ?: return@patch call.error(HttpStatusCode.BadRequest, "too_long")
                val mentions = validMentions(a.chat, req.mentions) ?: return@patch call.error(HttpStatusCode.BadRequest, "invalid_mention")
                if (!store.editMessage(a.chat.id, msg.id, a.me, body, deps.clock.nowMs(), mentions)) return@patch call.error(HttpStatusCode.Conflict, "deleted")
                fanOut(a.chat, a.me, mentions.toSet() - msg.mentions.toSet()) // only a new mention rings again
                call.respond((store.message(a.chat.id, msg.id) ?: return@patch call.error(HttpStatusCode.NotFound, "not_found")).toWire())
            }

            delete("/v1/chats/{id}/messages/{msgId}") {
                val a = call.access() ?: return@delete
                val msg = store.message(a.chat.id, call.parameters["msgId"]!!) ?: return@delete call.error(HttpStatusCode.NotFound, "not_found")
                if (msg.authorId != a.me && !a.isAdmin) return@delete call.error(HttpStatusCode.Forbidden, "not_author")
                if (!store.deleteMessage(a.chat.id, msg.id, a.me, byAdmin = a.isAdmin)) return@delete call.error(HttpStatusCode.Conflict, "deleted")
                fanOut(a.chat, a.me)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        post("/v1/chats/{id}/read") {
            val a = call.access() ?: return@post
            if (!a.isMember) return@post call.error(HttpStatusCode.Forbidden, "not_member")
            if (store.markRead(a.chat.id, a.me, call.receive<ReadRequest>().rev, deps.clock.nowMs())) emitReceipts(a.chat, a.me)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/v1/chats/{id}/messages/{msgId}/receipts") {
            val a = call.access() ?: return@get
            val msg = store.message(a.chat.id, call.parameters["msgId"]!!) ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
            call.respond(store.messageReceipts(a.chat.id, msg.createdRev))
        }

        post("/v1/chats/{id}/seen") {
            val a = call.access() ?: return@post
            if (!a.isMember) return@post call.error(HttpStatusCode.Forbidden, "not_member")
            if (store.markSeen(a.chat.id, a.me, call.receive<SeenRequest>().rev, deps.clock.nowMs())) emitReceipts(a.chat, a.me)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
