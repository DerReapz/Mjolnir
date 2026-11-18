package app.mjolnir.server

import app.mjolnir.protocol.ClaimRequest
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
                val code = PairingCode.normalize(call.receive<ClaimRequest>().code)
                    ?: return@post call.notFound()
                val now = deps.clock.nowMs()
                val open = store.openPairingByCode(code, now) ?: return@post call.notFound()
                if (open.ownerId == me.deviceId) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("own_code"))
                }
                val row = store.claimPairing(code, me.deviceId, now) ?: return@post call.notFound()
                val owner = store.device(row.ownerId) ?: return@post call.notFound()
                hub.emit(row.ownerId, RelayEvent.PairRequested(row.id, DeviceRow(me.deviceId, me.name, me.kind, null).toPeer()))
                call.respond(HttpStatusCode.Accepted, ClaimResponse(row.id, owner.toPeer()))
            }
        }

        get("/v1/pairings/{id}") {
            val me = call.me()
            val row = store.pairing(call.parameters["id"]!!)
            if (row == null || (row.ownerId != me.deviceId && row.claimerId != me.deviceId)) return@get call.notFound()
            val state = effectiveState(row, deps.clock.nowMs())
            val otherId = if (row.ownerId == me.deviceId) row.claimerId else row.ownerId
            val peer = if (state == PairingState.ACCEPTED) otherId?.let { store.device(it)?.toPeer() } else null
            call.respond(PairingStatus(state, peer))
        }

        post("/v1/pairings/{id}/accept") {
            val me = call.me()
            val id = call.parameters["id"]!!
            when (store.resolvePairing(id, me.deviceId, accept = true, nowMs = deps.clock.nowMs())) {
                Store.Resolved.NOT_FOUND -> call.notFound()
                Store.Resolved.FORBIDDEN -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                Store.Resolved.ALREADY_ACCEPTED -> call.respond(HttpStatusCode.NoContent)
                Store.Resolved.WON -> {
                    val row = store.pairing(id)!!
                    val owner = store.device(row.ownerId)!!
                    val claimer = store.device(row.claimerId!!)!!
                    hub.emit(owner.id, RelayEvent.Paired(claimer.toPeer()))
                    hub.emit(claimer.id, RelayEvent.Paired(owner.toPeer()))
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        post("/v1/pairings/{id}/reject") {
            val me = call.me()
            when (store.resolvePairing(call.parameters["id"]!!, me.deviceId, accept = false, nowMs = deps.clock.nowMs())) {
                Store.Resolved.NOT_FOUND, Store.Resolved.ALREADY_ACCEPTED -> call.notFound()
                Store.Resolved.FORBIDDEN -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                Store.Resolved.WON -> call.respond(HttpStatusCode.NoContent)
            }
        }

        get("/v1/peers") {
            call.respond(store.peersOf(call.me().deviceId).map { it.toPeer() })
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
            hub.add(me.deviceId, this)
            try {
                for (frame in incoming) { /* client frames are ignored; the channel is server-push */ }
            } finally {
                hub.remove(me.deviceId, this)
            }
        }
    }
}

/** EXPIRED is computed: a PENDING or CLAIMED pairing past its deadline. */
fun effectiveState(row: PairingRow, nowMs: Long): PairingState =
    if ((row.state == PairingState.PENDING || row.state == PairingState.CLAIMED) && nowMs > row.expiresAtMs) PairingState.EXPIRED
    else row.state
