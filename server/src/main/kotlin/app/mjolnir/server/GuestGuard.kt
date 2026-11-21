package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.ErrorResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond

/** One path segment that names an id: no dots, slashes or escapes, so it can never walk to another route. */
private const val ID = "[A-Za-z0-9_-]+"

/** The only routes a guest token may use; everything else answers 403 `guest`. */
private val GUEST_ROUTES: List<Pair<HttpMethod, Regex>> = listOf(
    HttpMethod.Post to "/v1/uploads",
    HttpMethod.Get to "/v1/uploads/$ID",
    HttpMethod.Put to "/v1/uploads/$ID",
    HttpMethod.Get to "/v1/files/$ID", // includes /v1/files/zip
    HttpMethod.Post to "/v1/send",
    HttpMethod.Get to "/v1/inbox",
    HttpMethod.Delete to "/v1/inbox/$ID",
    HttpMethod.Get to "/v1/ws",
    HttpMethod.Get to "/v1/devices/me",
    HttpMethod.Post to "/v1/guest/ticket",
).map { (m, p) -> m to Regex(p) }

internal fun guestRouteAllowed(method: HttpMethod, path: String): Boolean =
    GUEST_ROUTES.any { (m, p) -> m == method && p.matches(path) }

/** True for a device that may act now: any non-guest, or a guest whose room is still open. */
fun RelayDeps.deviceActive(device: DeviceRow): Boolean =
    device.kind != DeviceKind.GUEST || store.roomOfGuest(device.id)?.let { it.expiresAtMs > clock.nowMs() } == true

class GuestGuardConfig {
    lateinit var deps: RelayDeps
}

/**
 * Runs before routing: a request whose token belongs to a guest may only reach [GUEST_ROUTES]. Default-deny, so a
 * route added later is closed to guests until it is put on the list. Requests without a guest token pass untouched.
 */
val GuestGuard = createApplicationPlugin("GuestGuard", ::GuestGuardConfig) {
    val deps = pluginConfig.deps
    onCall { call ->
        if (guestRouteAllowed(call.request.httpMethod, call.request.path())) return@onCall
        // Any scheme: whatever blob the bearer provider would read must not be a guest's token.
        val token = (runCatching { call.request.parseAuthorizationHeader() }.getOrNull() as? HttpAuthHeader.Single)?.blob
            ?: return@onCall
        if (deps.store.deviceByTokenHash(sha256Hex(token))?.kind == DeviceKind.GUEST) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("guest"))
        }
    }
}
