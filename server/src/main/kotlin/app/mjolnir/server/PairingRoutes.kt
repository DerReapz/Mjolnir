package app.mjolnir.server

import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.mjolnir.protocol.ClaimRequest
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.SpaceRef
import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.PairingCode
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.RelayEvent
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
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket

private fun ApplicationCall.me() = principal<DevicePrincipal>()!!

private suspend fun ApplicationCall.notFound() = respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))

const val HEARTBEAT_FRAME = """{"type":"heartbeat"}"""

fun Route.pairingRoutes(deps: RelayDeps) {
    val store = deps.store
    val hub = deps.hub

    authenticate("device") {
        post("/v1/pairings") {
            val me = call.me()
            val now = deps.clock.nowMs()
            val row = store.createPairing(me.deviceId, PairingCode.generate(), now)
            call.respond(HttpStatusCode.Created, PairingCodeResponse(row.code, row.expiresAtMs))
        }

        rateLimit(ClaimLimit) {
            post("/v1/pairings/claim") {
                val me = call.me()
                val req = call.receive<ClaimRequest>()
                val code = PairingCode.normalize(req.code) ?: return@post call.notFound()
                val now = deps.clock.nowMs()
                val open = store.openPairingByCode(code, now) ?: return@post call.notFound()
                if (open.ownerId == me.deviceId) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("own_code"))
                }
                if (req.expect != open.kind) {
                    val error = if (open.kind == CodeKind.SPACE_INVITE) "wrong_kind_space" else "wrong_kind_pairing"
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                }
                var space: SpaceRef? = null
                if (open.kind == CodeKind.SPACE_INVITE) {
                    // The inviter must still administer the space: a transferred or deleted space voids its old invites.
                    // guest check added by guest-room plan
                    val s = open.spaceId?.let { store.space(it) }
                    if (s == null || s.adminId != open.ownerId) return@post call.notFound()
                    space = s.toRef()
                    if (store.members(s.id).any { it.id == me.deviceId }) {
                        return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("already_member"))
                    }
                    if (store.spacesOf(me.deviceId).size >= deps.limits.maxSpacesPerDevice) {
                        return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("space_limit"))
                    }
                    if (store.members(s.id).size >= deps.limits.maxMembersPerSpace) {
                        return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("member_limit"))
                    }
                }
                val row = store.claimPairing(code, me.deviceId, now) ?: return@post call.notFound()
                val owner = store.device(row.ownerId) ?: return@post call.notFound()
                hub.emit(row.ownerId, RelayEvent.PairRequested(row.id, DeviceRow(me.deviceId, me.name, me.kind, null).toPeer(), space))
                call.respond(HttpStatusCode.Accepted, ClaimResponse(row.id, owner.toPeer(), row.kind, space))
            }
        }

        get("/v1/pairings/{id}") {
            val me = call.me()
            val row = store.pairing(call.parameters["id"]!!)
            if (row == null || (row.ownerId != me.deviceId && row.claimerId != me.deviceId)) return@get call.notFound()
            val state = effectiveState(row, deps.clock.nowMs())
            val otherId = if (row.ownerId == me.deviceId) row.claimerId else row.ownerId
            val peer = if (state == PairingState.ACCEPTED) otherId?.let { store.device(it)?.toPeer() } else null
            val space = if (row.kind == CodeKind.SPACE_INVITE) row.spaceId?.let { store.space(it)?.toRef() } else null
            call.respond(PairingStatus(state, peer, space))
        }

        post("/v1/pairings/{id}/accept") {
            val me = call.me()
            val id = call.parameters["id"]!!
            val result = store.resolvePairing(id, me.deviceId, accept = true, nowMs = deps.clock.nowMs(), maxMembers = deps.limits.maxMembersPerSpace, maxSpaces = deps.limits.maxSpacesPerDevice)
            when (result) {
                Store.Resolved.NOT_FOUND -> call.notFound()
                Store.Resolved.FULL -> call.respond(HttpStatusCode.Conflict, ErrorResponse("member_limit"))
                Store.Resolved.SPACE_LIMIT -> call.respond(HttpStatusCode.Conflict, ErrorResponse("space_limit"))
                Store.Resolved.FORBIDDEN -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                Store.Resolved.ALREADY_ACCEPTED -> call.respond(HttpStatusCode.NoContent)
                Store.Resolved.WON -> {
                    val row = store.pairing(id)!!
                    if (row.kind == CodeKind.SPACE_INVITE) {
                        emitSpaceChanged(deps, row.spaceId!!)
                    } else {
                        val owner = store.device(row.ownerId) ?: return@post call.notFound()
                        val claimer = store.device(row.claimerId!!) ?: return@post call.notFound()
                        hub.emit(owner.id, RelayEvent.Paired(claimer.toPeer()))
                        hub.emit(claimer.id, RelayEvent.Paired(owner.toPeer()))
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        post("/v1/pairings/{id}/reject") {
            val me = call.me()
            when (store.resolvePairing(call.parameters["id"]!!, me.deviceId, accept = false, nowMs = deps.clock.nowMs())) {
                Store.Resolved.NOT_FOUND, Store.Resolved.ALREADY_ACCEPTED, Store.Resolved.FULL, Store.Resolved.SPACE_LIMIT -> call.notFound()
                Store.Resolved.FORBIDDEN -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                Store.Resolved.WON -> call.respond(HttpStatusCode.NoContent)
            }
        }

        get("/v1/peers") {
            val me = call.me().deviceId
            val paired = store.peersOf(me)
            val pairedIds = paired.map { it.id }.toSet()
            // A device that is paired shows once, under paired; otherwise once per shared space.
            val viaSpaces = store.spacesOf(me).flatMap { s ->
                store.members(s.id).filter { it.id != me && it.id !in pairedIds }.map { Peer(it.id, it.name, it.kind, s.toRef()) }
            }
            call.respond(paired.map { it.toPeer() } + viaSpaces)
        }

        delete("/v1/peers/{deviceId}") {
            val me = call.me()
            val other = call.parameters["deviceId"]!!
            if (!store.areLinked(me.deviceId, other)) return@delete call.notFound()
            store.unlink(me.deviceId, other)
            hub.emit(other, RelayEvent.Unpaired(me.deviceId))
            call.respond(HttpStatusCode.NoContent)
        }

        webSocket("/v1/ws") {
            val me = call.me()
            val sink = WebSocketSink(this)
            hub.add(me.deviceId, sink)
            val heartbeat = launch {
                while (true) {
                    delay(deps.heartbeatMs)
                    send(Frame.Text(HEARTBEAT_FRAME))
                }
            }
            try {
                for (frame in incoming) { /* client frames are ignored; the channel is server-push */ }
            } finally {
                heartbeat.cancel()
                hub.remove(me.deviceId, sink)
            }
        }
    }
}

/** EXPIRED is computed: a PENDING or CLAIMED pairing past its deadline. */
fun effectiveState(row: PairingRow, nowMs: Long): PairingState =
    if ((row.state == PairingState.PENDING || row.state == PairingState.CLAIMED) && nowMs > row.expiresAtMs) PairingState.EXPIRED
    else row.state
