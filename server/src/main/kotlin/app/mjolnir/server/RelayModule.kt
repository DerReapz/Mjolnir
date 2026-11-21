package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.MjolnirJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
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
import io.ktor.server.websocket.pingPeriod
import app.mjolnir.protocol.Limits
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.ktor.server.plugins.PayloadTooLargeException
import kotlinx.serialization.SerializationException
import kotlin.io.path.createTempDirectory
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun interface Clock { fun nowMs(): Long }

object SystemClock : Clock { override fun nowMs(): Long = System.currentTimeMillis() }

data class RelayLimits(
    val registrationsPerHourPerIp: Int = 5,
    val claimsPerMinute: Int = 10,
    val sendsPerMinute: Int = 60,
    val maxSpacesPerDevice: Int = 50,
    val maxMembersPerSpace: Int = 50,
    /** Per-room storage cap from `MJOLNIR_GUEST_MAX_MB`; null: the global cap (it can only lower that). */
    val guestMaxBytes: Long? = null,
    val guestJoinsPerMinutePerIp: Int = 10,
    val roomsPerHourPerDevice: Int = 30,
)

data class RelayDeps(
    val store: Store,
    val clock: Clock = SystemClock,
    val limits: RelayLimits = RelayLimits(),
    /** Scope for fire-and-forget work such as push pings and event pushes; cancelled when the application stops. */
    val asyncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val hub: SessionHub = SessionHub(asyncScope),
    val files: FileStorage = FileStorage(createTempDirectory("mjolnir-files").toFile()),
    val push: PushSender = NoopPushSender,
    /** Interval of the `{"type":"heartbeat"}` text frame sent on every open WebSocket (clients use it to detect dead links). */
    val heartbeatMs: Long = 30_000,
    /** How often expired guest rooms are ended (their guest removed and its sockets closed). */
    val roomSweepMs: Long = 15_000,
    val tickets: DownloadTickets = DownloadTickets(),
)

/** The registration rate-limit key: Fly's own `Fly-Client-IP` when present, else the last proxy hop. */
internal fun clientIp(call: ApplicationCall): String =
    call.request.header("Fly-Client-IP")?.trim()?.takeIf { it.isNotEmpty() } ?: call.request.origin.remoteHost

data class DevicePrincipal(val deviceId: String, val name: String, val kind: DeviceKind)

val RegisterLimit = RateLimitName("register")
val ClaimLimit = RateLimitName("claim")
val SendLimit = RateLimitName("send")

fun Application.relayModule(deps: RelayDeps) {
    install(ContentNegotiation) { json(MjolnirJson) }
    install(GuestGuard) { this.deps = deps }
    // Only the last X-Forwarded-For hop is added by our own proxy; earlier entries are whatever the client sent.
    install(XForwardedHeaders) { useLastProxy() }
    install(RequestBodyLimit) {
        // JSON bodies are capped at 1 MiB. Upload chunks are exempt: the PUT handler itself reads at most the
        // Content-Range length (≤ UPLOAD_CHUNK_BYTES, else 400) and rejects any extra byte.
        bodyLimit { call ->
            if (isChunkUpload(call.request.httpMethod.value, call.request.path())) Long.MAX_VALUE
            else Limits.MAX_BODY_BYTES
        }
    }
    install(WebSockets) { pingPeriod = 30.seconds }
    install(StatusPages) {
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
        }
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
                    ?.takeIf { deps.deviceActive(it) } // a guest token dies with its room
                    ?.also { deps.store.touchLastSeen(it.id, deps.clock.nowMs()) }
                    ?.let { DevicePrincipal(it.id, it.name, it.kind) }
            }
        }
    }
    install(RateLimit) {
        register(RegisterLimit) {
            rateLimiter(limit = deps.limits.registrationsPerHourPerIp, refillPeriod = 1.hours)
            requestKey { call -> clientIp(call) }
        }
        register(ClaimLimit) {
            rateLimiter(limit = deps.limits.claimsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.principal<DevicePrincipal>()?.deviceId ?: call.request.origin.remoteHost }
        }
        register(GuestJoinLimit) {
            rateLimiter(limit = deps.limits.guestJoinsPerMinutePerIp, refillPeriod = 1.minutes)
            requestKey { call -> clientIp(call) }
        }
        register(RoomLimit) {
            rateLimiter(limit = deps.limits.roomsPerHourPerDevice, refillPeriod = 1.hours)
            requestKey { call -> call.principal<DevicePrincipal>()?.deviceId ?: call.request.origin.remoteHost }
        }
        register(SendLimit) {
            rateLimiter(limit = deps.limits.sendsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.principal<DevicePrincipal>()?.deviceId ?: call.request.origin.remoteHost }
        }
    }
    val purgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    purgeScope.launch {
        while (true) {
            delay(10.minutes)
            try {
                purge(deps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("purge failed", e)
            }
        }
    }
    purgeScope.launch {
        while (true) {
            delay(deps.roomSweepMs)
            try {
                endExpiredRooms(deps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("room sweep failed", e)
            }
        }
    }
    monitor.subscribe(ApplicationStopping) { purgeScope.cancel(); deps.asyncScope.cancel() }
    routing {
        get("/health") { call.respondText("ok") }
        deviceRoutes(deps)
        pairingRoutes(deps)
        spaceRoutes(deps)
        chatRoutes(deps)
        sendRoutes(deps)
        fileRoutes(deps)
        roomRoutes(deps)
        guestPageRoutes()
    }
}
