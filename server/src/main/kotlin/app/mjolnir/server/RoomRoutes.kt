package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.GuestJoinRequest
import app.mjolnir.protocol.GuestJoinResponse
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.RoomResponse
import app.mjolnir.protocol.RoomStatus
import app.mjolnir.protocol.TicketRequest
import app.mjolnir.protocol.TicketResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

val GuestJoinLimit = RateLimitName("guest_join")
val RoomLimit = RateLimitName("room")

private const val MIB = 1_048_576L

/** What a download ticket stands in for: one file, or one folder of a delivery as a zip, for [deviceId]. */
data class TicketGrant(
    val deviceId: String,
    val fileId: String?,
    val deliveryId: String?,
    val folder: String?,
    val expiresAtMs: Long,
)

/**
 * Single-use download tickets, so a browser can download with a plain link instead of a bearer header. Held in
 * memory and only as SHA-256 hashes; each lives [Limits.DOWNLOAD_TICKET_TTL_MS] and is spent by its first use.
 */
class DownloadTickets(private val maxLivePerDevice: Int = 100) {
    private val grants = ConcurrentHashMap<String, TicketGrant>()

    /** A new ticket for [grant]; null when the device already holds [maxLivePerDevice] live ones. */
    fun issue(grant: TicketGrant, nowMs: Long): String? {
        grants.values.removeIf { it.expiresAtMs <= nowMs }
        if (grants.values.count { it.deviceId == grant.deviceId } >= maxLivePerDevice) return null
        val ticket = newToken()
        grants[sha256Hex(ticket)] = grant
        return ticket
    }

    /** Spends [ticket] whatever happens next; its grant if it was live. */
    fun redeem(ticket: String, nowMs: Long): TicketGrant? =
        grants.remove(sha256Hex(ticket))?.takeIf { it.expiresAtMs > nowMs }
}

/** The per-room storage cap: `MJOLNIR_GUEST_MAX_MB` if set, never above the relay's global cap. */
fun RelayDeps.roomCapBytes(): Long = minOf(limits.guestMaxBytes ?: Long.MAX_VALUE, files.maxTotalBytes)

/** Ends room [roomId] (see [Store.endRoom]), deletes its blobs, closes the guest's sockets and tells the owner. */
suspend fun endRoomAndNotify(deps: RelayDeps, roomId: String): Boolean {
    val ended = deps.store.endRoom(roomId) ?: return false
    withContext(Dispatchers.IO) { ended.fileIdsToDelete.forEach(deps.files::delete) }
    ended.guestId?.let { deps.hub.disconnect(it, "room_ended") }
    deps.hub.emit(ended.ownerId, RelayEvent.RoomEnded(roomId))
    return true
}

/** Ends every room past its expiry; run by the room sweeper. */
suspend fun endExpiredRooms(deps: RelayDeps) {
    deps.store.expiredRooms(deps.clock.nowMs()).forEach { endRoomAndNotify(deps, it) }
}

private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String) = respond(status, ErrorResponse(code))

fun Route.roomRoutes(deps: RelayDeps) {
    val store = deps.store

    rateLimit(GuestJoinLimit) {
        post("/v1/guest/join") {
            // The secret is a credential: it is hashed at once and never logged or echoed.
            val secretHash = sha256Hex(call.receive<GuestJoinRequest>().secret)
            val now = deps.clock.nowMs()
            val room = store.roomBySecretHash(secretHash)
            if (room == null || room.expiresAtMs <= now) return@post call.error(HttpStatusCode.Gone, "closed")
            if (room.guestId != null) return@post call.error(HttpStatusCode.Conflict, "in_use")
            val owner = store.device(room.ownerId) ?: return@post call.error(HttpStatusCode.Gone, "closed")
            val token = newToken()
            val guest = when (val joined = store.joinRoom(room.id, sha256Hex(token), now)) {
                is Store.JoinResult.Joined -> joined.guest
                Store.JoinResult.InUse -> return@post call.error(HttpStatusCode.Conflict, "in_use")
                Store.JoinResult.Closed -> return@post call.error(HttpStatusCode.Gone, "closed")
            }
            deps.hub.emit(owner.id, RelayEvent.GuestJoined(room.id, guest.toPeer()))
            call.respond(
                HttpStatusCode.Created,
                GuestJoinResponse(guest.id, token, room.id, owner.toPeer(), room.expiresAtMs, (deps.files.maxFileBytes / MIB).toInt()),
            )
        }
    }

    authenticate("device") {
        rateLimit(RoomLimit) {
            post("/v1/rooms") {
                val me = call.principal<DevicePrincipal>()!!
                if (me.kind == DeviceKind.GUEST) return@post call.error(HttpStatusCode.Forbidden, "guest")
                endExpiredRooms(deps) // an expired room, swept or not, never blocks a new one
                val secret = newToken()
                val row = store.createRoom(me.deviceId, sha256Hex(secret), deps.clock.nowMs(), Limits.GUEST_ROOM_TTL_MS)
                    ?: return@post call.error(HttpStatusCode.Conflict, "room_open")
                call.respond(HttpStatusCode.Created, RoomResponse(row.id, secret, row.expiresAtMs))
            }
        }

        get("/v1/rooms/current") {
            val me = call.principal<DevicePrincipal>()!!
            val now = deps.clock.nowMs()
            val room = store.openRoomOf(me.deviceId, now) ?: return@get call.respond(HttpStatusCode.NoContent)
            val guest = room.guestId?.let { store.device(it)?.toPeer() }
            call.respond(RoomStatus(room.id, room.expiresAtMs, now, guest))
        }

        delete("/v1/rooms/{id}") {
            val me = call.principal<DevicePrincipal>()!!
            val room = store.room(call.parameters["id"]!!)
            if (room == null || room.ownerId != me.deviceId) return@delete call.error(HttpStatusCode.NotFound, "not_found")
            endRoomAndNotify(deps, room.id)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/v1/guest/ticket") {
            val me = call.principal<DevicePrincipal>()!!
            val req = call.receive<TicketRequest>()
            val now = deps.clock.nowMs()
            val expires = now + Limits.DOWNLOAD_TICKET_TTL_MS
            val grant = when {
                req.fileId != null && req.deliveryId == null && req.folder == null -> {
                    store.fileForDownload(req.fileId!!, me.deviceId) ?: return@post call.error(HttpStatusCode.NotFound, "not_found")
                    TicketGrant(me.deviceId, req.fileId, null, null, expires)
                }
                req.fileId == null && req.deliveryId != null && req.folder != null -> {
                    store.folderOf(req.deliveryId!!, me.deviceId, req.folder!!) ?: return@post call.error(HttpStatusCode.NotFound, "not_found")
                    TicketGrant(me.deviceId, null, req.deliveryId, req.folder, expires)
                }
                else -> return@post call.error(HttpStatusCode.BadRequest, "bad_request")
            }
            val ticket = deps.tickets.issue(grant, now) ?: return@post call.error(HttpStatusCode.TooManyRequests, "too_many_tickets")
            call.respond(TicketResponse(ticket))
        }
    }
}
