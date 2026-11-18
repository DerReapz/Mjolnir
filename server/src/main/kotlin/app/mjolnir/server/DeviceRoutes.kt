package app.mjolnir.server

import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.RegisterRequest
import app.mjolnir.protocol.RegisterResponse
import app.mjolnir.protocol.RenameRequest
import app.mjolnir.protocol.Peer
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Returns the trimmed name, or null when it is empty or longer than the limit. */
private fun validName(raw: String): String? =
    raw.trim().takeIf { it.isNotEmpty() && it.length <= Limits.MAX_DEVICE_NAME_LENGTH }

fun Route.deviceRoutes(deps: RelayDeps) {
    route("/v1/devices") {
        rateLimit(RegisterLimit) {
            post {
                val req = call.receive<RegisterRequest>()
                val name = validName(req.name)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_name"))
                val token = newToken()
                val row = deps.store.createDevice(name, req.kind, sha256Hex(token), deps.clock.nowMs())
                call.respond(HttpStatusCode.Created, RegisterResponse(row.id, token))
            }
        }
        authenticate("device") {
            get("/me") {
                val p = call.principal<DevicePrincipal>()!!
                call.respond(Peer(p.deviceId, p.name, p.kind))
            }
            patch("/me") {
                val p = call.principal<DevicePrincipal>()!!
                val name = validName(call.receive<RenameRequest>().name)
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_name"))
                deps.store.rename(p.deviceId, name)
                call.respond(Peer(p.deviceId, name, p.kind))
            }
        }
    }
}
