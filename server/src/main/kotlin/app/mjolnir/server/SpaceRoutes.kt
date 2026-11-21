package app.mjolnir.server

import app.mjolnir.protocol.CreateSpaceRequest
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.PairingCode
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.RenameSpaceRequest
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.SpaceRef
import app.mjolnir.protocol.TransferAdminRequest
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

fun SpaceRow.toRef() = SpaceRef(id, name)

/** True when [adminId] has not been seen for over 30 days and has no live connection (so it may be taken over). */
fun RelayDeps.adminSilent(adminId: String): Boolean =
    !hub.isConnected(adminId) && clock.nowMs() - store.lastSeen(adminId) > Limits.ADMIN_SILENT_TAKEOVER_MS

/** The wire view of [row]: its members and whether its admin is silent. */
fun RelayDeps.spaceView(row: SpaceRow): Space =
    Space(row.id, row.name, row.adminId, store.members(row.id).map { it.toPeer() }, adminSilent(row.adminId))

/** Trimmed name, or null when empty, too long or containing control characters (device names have no such rule; this one is stricter). */
private fun validSpaceName(raw: String): String? =
    raw.trim().takeIf { it.isNotEmpty() && it.length <= Limits.MAX_SPACE_NAME_LENGTH && it.none(Char::isISOControl) }

private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String) = respond(status, ErrorResponse(code))

/** Tells every current member of [spaceId], plus [also], to re-read the space. */
fun emitSpaceChanged(deps: RelayDeps, spaceId: String, also: Collection<String> = emptyList()) {
    val ids = deps.store.members(spaceId).map { it.id } + also
    ids.toSet().forEach { deps.hub.emit(it, RelayEvent.SpaceChanged(spaceId)) }
}

fun Route.spaceRoutes(deps: RelayDeps) {
    val store = deps.store

    /** The space if the caller is a member; otherwise answers 404 and returns null. */
    suspend fun ApplicationCall.memberSpace(): Pair<SpaceRow, String>? {
        val me = principal<DevicePrincipal>()!!.deviceId
        val row = store.space(parameters["id"]!!)
        if (row == null || store.members(row.id).none { it.id == me }) {
            error(HttpStatusCode.NotFound, "not_found")
            return null
        }
        return row to me
    }

    /** The space if the caller is its admin; 404 for a non-member, 403 for a plain member. */
    suspend fun ApplicationCall.adminSpace(): Pair<SpaceRow, String>? {
        val (row, me) = memberSpace() ?: return null
        if (row.adminId != me) {
            error(HttpStatusCode.Forbidden, "not_admin")
            return null
        }
        return row to me
    }

    authenticate("device") {
        post("/v1/spaces") {
            // guest check added by guest-room plan
            val me = call.principal<DevicePrincipal>()!!.deviceId
            val name = validSpaceName(call.receive<CreateSpaceRequest>().name)
                ?: return@post call.error(HttpStatusCode.BadRequest, "invalid_name")
            if (store.spacesOf(me).size >= deps.limits.maxSpacesPerDevice) {
                return@post call.error(HttpStatusCode.Conflict, "space_limit")
            }
            val row = store.createSpace(me, name, deps.clock.nowMs())
            call.respond(HttpStatusCode.Created, deps.spaceView(row))
        }

        get("/v1/spaces") {
            val me = call.principal<DevicePrincipal>()!!.deviceId
            call.respond(store.spacesOf(me).map { deps.spaceView(it) })
        }

        patch("/v1/spaces/{id}") {
            val (row, me) = call.adminSpace() ?: return@patch
            val name = validSpaceName(call.receive<RenameSpaceRequest>().name)
                ?: return@patch call.error(HttpStatusCode.BadRequest, "invalid_name")
            if (!store.renameSpace(row.id, me, name)) return@patch call.error(HttpStatusCode.Forbidden, "not_admin")
            emitSpaceChanged(deps, row.id)
            call.respond(deps.spaceView(row.copy(name = name)))
        }

        delete("/v1/spaces/{id}") {
            val (row, me) = call.adminSpace() ?: return@delete
            val members = store.deleteSpace(row.id, me) ?: return@delete call.error(HttpStatusCode.Forbidden, "not_admin")
            members.filter { it != me }.forEach { deps.hub.emit(it, RelayEvent.SpaceDeleted(row.toRef())) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/v1/spaces/{id}/invites") {
            val (row, me) = call.adminSpace() ?: return@post
            val made = store.createPairing(me, PairingCode.generate(), deps.clock.nowMs(), CodeKind.SPACE_INVITE, row.id)
            call.respond(HttpStatusCode.Created, PairingCodeResponse(made.code, made.expiresAtMs))
        }

        delete("/v1/spaces/{id}/members/{deviceId}") {
            val (row, me) = call.memberSpace() ?: return@delete
            val target = call.parameters["deviceId"]!!
            when (store.removeMember(row.id, target, me)) {
                Store.Removed.NOT_ADMIN -> return@delete call.error(HttpStatusCode.Forbidden, "not_admin")
                Store.Removed.ADMIN_MUST_TRANSFER -> return@delete call.error(HttpStatusCode.Conflict, "admin_must_transfer")
                Store.Removed.NOT_MEMBER -> return@delete call.error(HttpStatusCode.NotFound, "not_found")
                Store.Removed.REMOVED -> Unit
            }
            emitSpaceChanged(deps, row.id, also = listOf(target))
            call.respond(HttpStatusCode.NoContent)
        }

        post("/v1/spaces/{id}/admin") {
            val (row, me) = call.adminSpace() ?: return@post
            val target = call.receive<TransferAdminRequest>().deviceId
            if (store.members(row.id).none { it.id == target }) return@post call.error(HttpStatusCode.NotFound, "not_found")
            if (!store.transferAdmin(row.id, me, target)) return@post call.error(HttpStatusCode.Forbidden, "not_admin")
            emitSpaceChanged(deps, row.id)
            call.respond(deps.spaceView(row.copy(adminId = target)))
        }

        post("/v1/spaces/{id}/takeover") {
            val (row, me) = call.memberSpace() ?: return@post
            if (row.adminId == me || !deps.adminSilent(row.adminId)) {
                return@post call.error(HttpStatusCode.Conflict, "admin_active")
            }
            if (!store.transferAdmin(row.id, row.adminId, me)) return@post call.error(HttpStatusCode.Conflict, "admin_active")
            emitSpaceChanged(deps, row.id)
            call.respond(deps.spaceView(row.copy(adminId = me)))
        }
    }
}
