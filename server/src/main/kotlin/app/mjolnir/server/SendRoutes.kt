package app.mjolnir.server

import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.SendRequest
import app.mjolnir.protocol.SendResponse
import app.mjolnir.protocol.cleanTabs
import app.mjolnir.protocol.isSafeTabUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.sendRoutes(deps: RelayDeps) {
    val store = deps.store

    authenticate("device") {
        rateLimit(SendLimit) {
            post("/v1/send") {
                val me = call.principal<DevicePrincipal>()!!
                val req = call.receive<SendRequest>()
                if (!store.areLinked(me.deviceId, req.to)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("not_paired"))
                }
                // Files arrive in a later task; until then a send carries tabs only.
                if (req.fileIds.isNotEmpty() || req.tabs.isEmpty() || req.tabs.size > Limits.MAX_TABS_PER_SEND ||
                    req.tabs.any { !isSafeTabUrl(it.url) }
                ) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_tabs"))
                }
                val tabs = cleanTabs(req.tabs)
                val now = deps.clock.nowMs()
                val id = store.createDelivery(me.deviceId, req.to, tabs, now)
                deps.hub.emit(req.to, RelayEvent.DeliveryArrived(Delivery(id, DeviceRow(me.deviceId, me.name, me.kind, null).toPeer(), tabs, now)))
                call.respond(HttpStatusCode.Accepted, SendResponse(id))
            }
        }

        get("/v1/inbox") {
            val me = call.principal<DevicePrincipal>()!!
            val out = store.inbox(me.deviceId, deps.clock.nowMs()).mapNotNull { row ->
                store.device(row.fromId)?.let { Delivery(row.id, it.toPeer(), row.tabs, row.sentAtMs) }
            }
            call.respond(out)
        }

        delete("/v1/inbox/{id}") {
            val me = call.principal<DevicePrincipal>()!!
            store.deleteDelivery(me.deviceId, call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
