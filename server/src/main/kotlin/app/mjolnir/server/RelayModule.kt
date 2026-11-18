package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.MjolnirJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.hours

fun interface Clock { fun nowMs(): Long }

object SystemClock : Clock { override fun nowMs(): Long = System.currentTimeMillis() }

data class RelayLimits(
    val registrationsPerHourPerIp: Int = 5,
    val claimsPerMinute: Int = 10,
    val sendsPerMinute: Int = 60,
)

data class RelayDeps(
    val store: Store,
    val clock: Clock = SystemClock,
    val limits: RelayLimits = RelayLimits(),
)

data class DevicePrincipal(val deviceId: String, val name: String, val kind: DeviceKind)

val RegisterLimit = RateLimitName("register")

fun Application.relayModule(deps: RelayDeps) {
    install(ContentNegotiation) { json(MjolnirJson) }
    install(XForwardedHeaders)
    install(WebSockets)
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad_request"))
        }
    }
    install(Authentication) {
        bearer("device") {
            authenticate { cred ->
                deps.store.deviceByTokenHash(sha256Hex(cred.token))
                    ?.let { DevicePrincipal(it.id, it.name, it.kind) }
            }
        }
    }
    install(RateLimit) {
        register(RegisterLimit) {
            rateLimiter(limit = deps.limits.registrationsPerHourPerIp, refillPeriod = 1.hours)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
    routing {
        get("/health") { call.respondText("ok") }
        deviceRoutes(deps)
    }
}
