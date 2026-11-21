package app.mjolnir.server

import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.SendRequest
import app.mjolnir.protocol.SendResponse
import app.mjolnir.protocol.SpaceRef
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.sendRoutes(deps: RelayDeps) {
    val store = deps.store

    fun ping(to: String) = deps.pingDevice(to)

    authenticate("device") {
        rateLimit(SendLimit) {
            post("/v1/send") {
                val me = call.principal<DevicePrincipal>()!!
                val req = call.receive<SendRequest>()
                // A guest sends only to its room's owner, directly (never in a space).
                if (me.kind == DeviceKind.GUEST && (req.spaceId != null || req.to != store.roomOfGuest(me.deviceId)?.ownerId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("guest"))
                }
                // Allowed when paired, or when the card the user clicked names a space both devices are in.
                // A given spaceId must always hold, since it is stored as the delivery's label.
                val space = req.spaceId?.let { id ->
                    if (req.to == me.deviceId || !store.shareSpace(me.deviceId, req.to, id)) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("not_in_space"))
                    }
                    store.space(id)
                }
                if (space == null && !store.areLinked(me.deviceId, req.to)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("not_paired"))
                }
                val ref = space?.let { SpaceRef(it.id, it.name) }
                // A delivery holds tabs or files, never both and never neither.
                if (req.tabs.isEmpty() == req.fileIds.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("tabs_or_files"))
                }
                val from = DeviceRow(me.deviceId, me.name, me.kind, null).toPeer()
                val now = deps.clock.nowMs()
                if (req.fileIds.isNotEmpty()) {
                    if (req.fileIds.size > Limits.MAX_FILES_PER_SEND) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_files"))
                    }
                    // Files sent to a guest count toward its room's storage cap.
                    if (store.device(req.to)?.kind == DeviceKind.GUEST &&
                        store.roomBytes(req.to) + store.unsentBytes(me.deviceId, req.fileIds) > deps.roomCapBytes()
                    ) {
                        return@post call.respond(HttpStatusCode.InsufficientStorage, ErrorResponse("relay_full"))
                    }
                    val (result, row) = store.createFileDelivery(me.deviceId, req.to, req.fileIds, now, ref?.id, ref?.name, deps.files.maxFileBytes)
                    return@post when (result) {
                        AttachResult.INVALID -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_files"))
                        AttachResult.NOT_ALLOWED -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("not_paired"))
                        AttachResult.ALREADY_SENT -> call.respond(HttpStatusCode.Conflict, ErrorResponse("already_sent"))
                        AttachResult.OK -> {
                            row!!
                            deps.hub.emit(req.to, RelayEvent.DeliveryArrived(Delivery(row.id, from, emptyList(), now, row.files, ref)))
                            ping(req.to)
                            call.respond(HttpStatusCode.Accepted, SendResponse(row.id))
                        }
                    }
                }
                if (req.tabs.size > Limits.MAX_TABS_PER_SEND || req.tabs.any { !isSafeTabUrl(it.url) }) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_tabs"))
                }
                val tabs = cleanTabs(req.tabs)
                val id = store.createDelivery(me.deviceId, req.to, tabs, now, spaceId = ref?.id, spaceName = ref?.name)
                deps.hub.emit(req.to, RelayEvent.DeliveryArrived(Delivery(id, from, tabs, now, space = ref)))
                ping(req.to)
                call.respond(HttpStatusCode.Accepted, SendResponse(id))
            }
        }

        get("/v1/inbox") {
            val me = call.principal<DevicePrincipal>()!!
            val out = store.inbox(me.deviceId, deps.clock.nowMs()).map { row ->
                // Devices are only ever deleted when their guest room ends; what the guest sent stays, still from "Guest".
                val from = store.device(row.fromId)?.toPeer() ?: Peer(row.fromId, "Guest", DeviceKind.GUEST)
                Delivery(row.id, from, row.tabs, row.sentAtMs, row.files, row.spaceId?.let { id -> SpaceRef(id, row.spaceName ?: "") })
            }
            call.respond(out)
        }

        delete("/v1/inbox/{id}") {
            val me = call.principal<DevicePrincipal>()!!
            val fileIds = store.deleteDelivery(me.deviceId, call.parameters["id"]!!, deps.clock.nowMs())
            withContext(Dispatchers.IO) { fileIds.forEach(deps.files::delete) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
