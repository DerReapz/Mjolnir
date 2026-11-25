package app.mjolnir.server

import app.mjolnir.protocol.CreateVoiceRoomRequest
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.VoiceRoom
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

private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String) = respond(status, ErrorResponse(code))

fun Route.voiceRoutes(deps: RelayDeps) {
    val store = deps.store

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
    }
}
