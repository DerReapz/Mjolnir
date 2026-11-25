package app.mjolnir.server

import app.mjolnir.protocol.CreateVoiceRoomRequest
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.MjolnirJson
import app.mjolnir.protocol.VoiceRoom
import app.mjolnir.protocol.VoiceRoster
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** The wire view of [row] with the members currently in it. */
fun RelayDeps.voiceRoomView(row: VoiceRoomRow): VoiceRoom =
    VoiceRoom(
        row.id, row.spaceId, row.name, row.creatorId,
        voice.participants(row.id).map { id -> store.device(id)?.toPeer() ?: Peer(id, "Unknown device", DeviceKind.PC) },
    )

/** Tells every member of the room's space that [row] changed. */
fun emitVoiceRoomChanged(deps: RelayDeps, row: VoiceRoomRow) {
    val event = RelayEvent.VoiceRoomChanged(deps.voiceRoomView(row))
    deps.store.members(row.spaceId).forEach { deps.hub.emit(it.id, event) }
}

/** [deviceId] left (or was removed from) [spaceId]: it leaves that space's voice rooms and the remaining members hear it. */
fun kickFromVoice(deps: RelayDeps, spaceId: String, deviceId: String) {
    for (room in deps.store.voiceRoomsIn(spaceId)) {
        if (deps.voice.leave(room.id, deviceId)) {
            deps.voice.onRemoved(room.id, listOf(deviceId))
            emitVoiceRoomChanged(deps, room)
        }
    }
}

/** The close code of every voice socket close the relay makes; the reason text says why. */
const val VOICE_CLOSE_CODE: Short = 4000

/** Largest Opus packet (RFC 6716); an audio frame from a client is `[seq: u16 BE][1..1275 bytes of Opus]`. */
private const val MAX_OPUS_BYTES = 1275

/** The close a client sends to leave a room for good (no grace); any other close keeps its slot for the grace. */
val VOICE_LEAVE = CloseReason(CloseReason.Codes.NORMAL, "leave")

private fun voiceClose(reason: String) = CloseReason(VOICE_CLOSE_CODE, reason)

/** Closes [link] with [reason] and returns at once; a link that does not close in time is cut. */
private fun RelayDeps.closeLink(link: VoiceLink, reason: String) {
    asyncScope.launch {
        val closed = try {
            withTimeoutOrNull(2_000) { link.session.close(voiceClose(reason)); true } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!closed) link.session.cancel()
    }
}

/** Sends [roomId]'s roster to [only], or else to every open socket in the room. */
private fun RelayDeps.sendRoster(roomId: String, only: VoiceLink? = null) {
    synchronized(voice.roomLockFor(roomId)) {
        val roster = voice.participants(roomId)
        val text = MjolnirJson.encodeToString(VoiceRoster.serializer(), VoiceRoster(participants = roster))
        val targets = if (only != null) listOf(only) else roster.mapNotNull { id -> voice.linkOf(id)?.takeIf { it.roomId == roomId } }
        // Rosters are rare; a socket that lets them pile up is stuck, and is cut (it may reconnect within the grace).
        for (l in targets) if (!l.control.trySend(text).isSuccess) l.session.cancel()
    }
}

/** [roomId]'s participants changed: its sockets get the roster, its space's members the room. */
private fun RelayDeps.voiceParticipantsChanged(roomId: String) {
    sendRoster(roomId)
    store.voiceRoom(roomId)?.let { emitVoiceRoomChanged(this, it) }
}

/** Relays one audio frame of [link] to the other open sockets of its room, prefixed with the sender's roster index. */
private fun RelayDeps.forward(link: VoiceLink, data: ByteArray) {
    if (data.size < 3 || data.size > 2 + MAX_OPUS_BYTES) return
    synchronized(voice.roomLockFor(link.roomId)) {
        if (voice.linkOf(link.deviceId) !== link) return
        val roster = voice.participants(link.roomId)
        val index = roster.indexOf(link.deviceId)
        if (index !in 0..255) return
        val out = ByteArray(data.size + 1)
        out[0] = index.toByte()
        data.copyInto(out, 1)
        for (id in roster) {
            if (id != link.deviceId) voice.linkOf(id)?.takeIf { it.roomId == link.roomId }?.offerAudio(out)
        }
    }
}

private class VoiceJoin(val replaced: VoiceLink?, val movedFrom: String?, val silent: Boolean)

/** Puts [link]'s device in its room and makes [link] its socket; null when the room is full (nothing changes then). */
private fun RelayDeps.joinVoice(link: VoiceLink): VoiceJoin? = synchronized(voice.lockFor(link.deviceId)) {
    val already = voice.roomOf(link.deviceId) == link.roomId
    val result = voice.join(link.roomId, link.deviceId, limits.maxVoicePerRoom)
    if (result == VoiceHub.JoinResult.FULL) return null
    voice.graces.remove(link.deviceId)?.cancel()
    val replaced = voice.links.put(link.deviceId, link)
    VoiceJoin(replaced, (result as? VoiceHub.JoinResult.MOVED)?.previousRoomId, already)
}

/**
 * [link] closed. A deliberate leave ([leave]: the client closed with [VOICE_LEAVE]) removes its device at once; a dropped link
 * keeps the device listed for [RelayDeps.voiceGraceMs], so a reconnect within it takes the slot back silently.
 * Nothing happens when the link was already replaced or kicked.
 */
private fun RelayDeps.voiceLinkClosed(link: VoiceLink, leave: Boolean) {
    val deviceId = link.deviceId
    val roomId = link.roomId
    var left = false
    synchronized(voice.lockFor(deviceId)) {
        if (!voice.links.remove(deviceId, link)) return
        if (voice.roomOf(deviceId) != roomId) return
        if (leave) {
            left = voice.leave(roomId, deviceId)
        } else {
            lateinit var job: Job
            job = asyncScope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(voiceGraceMs)
                    var gone = false
                    synchronized(voice.lockFor(deviceId)) {
                        if (voice.graces[deviceId] === job && voice.links[deviceId] == null) gone = voice.leave(roomId, deviceId)
                        voice.graces.remove(deviceId, job)
                    }
                    if (gone) {
                        voiceParticipantsChanged(roomId)
                        onVoiceChanged(deviceId)
                    }
                } finally {
                    voice.graces.remove(deviceId, job)
                }
            }
            voice.graces.put(deviceId, job)?.cancel()
            job.start()
        }
    }
    if (left) {
        voiceParticipantsChanged(roomId)
        onVoiceChanged(deviceId)
    }
}

/**
 * The relay removed [deviceIds] from [roomId] (the room was deleted, or they left or were removed from its space): their
 * sockets close at once (`room_deleted` / `removed`), with no grace, and the rest of the room gets the new roster.
 */
private fun RelayDeps.kickedFromVoice(roomId: String, deviceIds: List<String>) {
    val reason = if (store.voiceRoom(roomId) == null) "room_deleted" else "removed"
    for (id in deviceIds) {
        val link = synchronized(voice.lockFor(id)) {
            if (voice.roomOf(id) == null) voice.graces.remove(id)?.cancel()
            voice.links[id]?.takeIf { it.roomId == roomId && voice.links.remove(id, it) }
        }
        link?.let { closeLink(it, reason) }
    }
    sendRoster(roomId)
    deviceIds.forEach { onVoiceChanged(it) }
}

private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String) = respond(status, ErrorResponse(code))

fun Route.voiceRoutes(deps: RelayDeps) {
    val store = deps.store
    deps.voice.onRemoved = { roomId, deviceIds -> deps.kickedFromVoice(roomId, deviceIds) }

    /** True when [deviceId] may be in [roomId]: the room exists and it is a member of the room's space. */
    fun mayJoin(roomId: String, deviceId: String): Boolean =
        store.voiceRoom(roomId)?.let { r -> store.members(r.spaceId).any { it.id == deviceId } } == true

    /** The room for `{id}` when the caller is a member of its space and its creator or the space admin; else answers 404 or 403 `not_owner` and returns null. */
    suspend fun ApplicationCall.manageable(): Pair<VoiceRoomRow, String>? {
        val me = principal<DevicePrincipal>()!!.deviceId
        val row = store.voiceRoom(parameters["id"]!!)
        val space = row?.let { store.space(it.spaceId) }
        if (row == null || space == null || store.members(space.id).none { it.id == me }) {
            error(HttpStatusCode.NotFound, "not_found")
            return null
        }
        if (row.creatorId != me && space.adminId != me) {
            error(HttpStatusCode.Forbidden, "not_owner")
            return null
        }
        return row to me
    }

    authenticate("device") {
        get("/v1/spaces/{id}/voice-rooms") {
            val me = call.principal<DevicePrincipal>()!!.deviceId
            val space = store.space(call.parameters["id"]!!)
            if (space == null || store.members(space.id).none { it.id == me }) return@get call.error(HttpStatusCode.NotFound, "not_found")
            call.respond(store.voiceRoomsIn(space.id).map { deps.voiceRoomView(it) })
        }

        post("/v1/spaces/{id}/voice-rooms") {
            val me = call.principal<DevicePrincipal>()!!.deviceId
            val space = store.space(call.parameters["id"]!!)
            if (space == null || store.members(space.id).none { it.id == me }) return@post call.error(HttpStatusCode.NotFound, "not_found")
            val name = validSpaceName(call.receive<CreateVoiceRoomRequest>().name)
                ?: return@post call.error(HttpStatusCode.BadRequest, "invalid_name")
            val row = store.createVoiceRoom(space.id, me, name, deps.clock.nowMs())
                ?: return@post call.error(HttpStatusCode.NotFound, "not_found")
            emitVoiceRoomChanged(deps, row)
            call.respond(HttpStatusCode.Created, deps.voiceRoomView(row))
        }

        patch("/v1/voice-rooms/{id}") {
            val (row, _) = call.manageable() ?: return@patch
            val name = validSpaceName(call.receive<CreateVoiceRoomRequest>().name)
                ?: return@patch call.error(HttpStatusCode.BadRequest, "invalid_name")
            if (!store.renameVoiceRoom(row.id, name)) return@patch call.error(HttpStatusCode.NotFound, "not_found")
            val renamed = row.copy(name = name)
            emitVoiceRoomChanged(deps, renamed)
            call.respond(deps.voiceRoomView(renamed))
        }

        delete("/v1/voice-rooms/{id}") {
            val (row, _) = call.manageable() ?: return@delete
            if (!store.deleteVoiceRoom(row.id)) return@delete call.error(HttpStatusCode.NotFound, "not_found")
            deps.voice.closeRoom(row.id)
            val event = RelayEvent.VoiceRoomDeleted(row.spaceId, row.id)
            store.members(row.spaceId).forEach { deps.hub.emit(it.id, event) }
            call.respond(HttpStatusCode.NoContent)
        }

        webSocket("/v1/voice/{roomId}") {
            val me = call.principal<DevicePrincipal>()!!
            val roomId = call.parameters["roomId"]!!
            // A guest is in no space, so it can never be in one of its rooms.
            if (me.kind == DeviceKind.GUEST || !mayJoin(roomId, me.deviceId)) return@webSocket close(voiceClose("not_found"))
            val link = VoiceLink(me.deviceId, roomId, this)
            val joined = deps.joinVoice(link) ?: return@webSocket close(voiceClose("room_full"))
            var leave = false
            var sender: Job? = null
            // Everything after the join runs inside the try, so the link can never stay registered.
            try {
                sender = launch {
                    try {
                        while (true) {
                            select<Unit> {
                                link.control.onReceive { send(Frame.Text(it)) } // first: a roster precedes audio indexed by it
                                link.audio.onReceive { link.queued.decrementAndGet(); send(Frame.Binary(true, it)) }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The socket is gone; the handler's own receive loop ends and cleans up.
                    }
                }
                joined.replaced?.takeIf { it !== link }?.let { deps.closeLink(it, "replaced") }
                deps.voice.afterJoin(roomId, me.deviceId)
                // A room delete or a removal from the space may have run between the first check and the join.
                if (!mayJoin(roomId, me.deviceId)) {
                    val reason = if (store.voiceRoom(roomId) == null) "room_deleted" else "removed"
                    var changed = false
                    synchronized(deps.voice.lockFor(me.deviceId)) {
                        deps.voice.links.remove(me.deviceId, link)
                        changed = deps.voice.leave(roomId, me.deviceId)
                    }
                    joined.movedFrom?.let { deps.voiceParticipantsChanged(it) }
                    if (changed) deps.voiceParticipantsChanged(roomId)
                    if (changed || joined.movedFrom != null) deps.onVoiceChanged(me.deviceId)
                    close(voiceClose(reason))
                    return@webSocket
                }
                joined.movedFrom?.let { deps.voiceParticipantsChanged(it) }
                if (joined.silent) {
                    deps.sendRoster(roomId, only = link)
                } else {
                    deps.voiceParticipantsChanged(roomId)
                    deps.onVoiceChanged(me.deviceId)
                }
                for (frame in incoming) if (frame is Frame.Binary) deps.forward(link, frame.data)
                // Only a normal close saying `leave` is a deliberate leave; anything else is a dropped link and gets the grace.
                leave = withTimeoutOrNull(1_000) { runCatching { closeReason.await() }.getOrNull() } == VOICE_LEAVE
            } finally {
                sender?.cancel()
                deps.voiceLinkClosed(link, leave)
            }
        }
    }
}
