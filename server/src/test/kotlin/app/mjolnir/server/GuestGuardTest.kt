package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.SendRequest
import app.mjolnir.protocol.Tab
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GuestGuardTest {
    /** The guest allowlist, written out independently of the guard's own table. */
    private val allowed = setOf(
        "POST /v1/uploads", "GET /v1/uploads/{}", "PUT /v1/uploads/{}",
        "GET /v1/files/{}", "GET /v1/files/zip",
        "POST /v1/send",
        "GET /v1/inbox", "DELETE /v1/inbox/{}",
        "GET /v1/ws", "GET /v1/devices/me", "POST /v1/guest/ticket",
    )

    /** "METHOD /path/{}" for a route, with each parameter segment as `{}`. */
    private fun template(route: RoutingNode): String? {
        var method: HttpMethod? = null
        val segments = ArrayDeque<String>()
        var node: RoutingNode? = route
        while (node != null) {
            when (val s = node.selector) {
                is HttpMethodRouteSelector -> method = s.method
                is PathSegmentConstantRouteSelector -> segments.addFirst(s.value)
                is PathSegmentParameterRouteSelector -> segments.addFirst("{}")
                else -> Unit
            }
            node = node.parent
        }
        return method?.let { "${it.value} /" + segments.joinToString("/") }
    }

    @Test fun everyNonAllowlistedRouteIs403Guest() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (_, guest) = guestOf(owner)
        val templates = application.plugin(RoutingRoot).getAllRoutes().mapNotNull(::template).toSet()
        assertTrue(templates.size > 25, "routes found: $templates")
        assertTrue(allowed.all { it in templates }, "every allowlisted route exists: ${allowed - templates}")
        for (t in templates - allowed) {
            val (method, path) = t.split(' ', limit = 2)
            val r = client.request(path.replace("{}", "x1")) { this.method = HttpMethod.parse(method); bearerAuth(guest.token) }
            assertEquals(HttpStatusCode.Forbidden, r.status, t)
            assertEquals("guest", r.body<ErrorResponse>().error, t)
        }
        // The same routes still work for a normal device (the guard only stops guests).
        assertEquals(HttpStatusCode.OK, client.get("/v1/peers") { bearerAuth(owner.token) }.status)
    }

    @Test fun allowlistedRoutesServeTheGuest() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (_, guest) = guestOf(owner)
        assertEquals(HttpStatusCode.OK, client.get("/v1/inbox") { bearerAuth(guest.token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/devices/me") { bearerAuth(guest.token) }.status)
        assertEquals(HttpStatusCode.Created, createUploadResponse(guest, "a.txt", "text/plain", 1).status)
        // Paths that only resemble an allowlisted one are refused.
        for (path in listOf("/v1/inbox/", "/v1/inbox/a/b", "/v1/uploads/..%2Fpeers", "/v1/files/a/../../peers")) {
            val r = client.get(path) { bearerAuth(guest.token) }
            assertNotEquals(HttpStatusCode.OK, r.status, path)
        }
    }

    @Test fun sendToNonOwnerIs403() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val (_, guest) = guestOf(pc)
        val toPhone = client.post("/v1/send") {
            bearerAuth(guest.token); contentType(ContentType.Application.Json); setBody(SendRequest(phone.deviceId, listOf(Tab("https://a.com"))))
        }
        assertEquals(HttpStatusCode.Forbidden, toPhone.status)
        assertEquals("guest", toPhone.body<ErrorResponse>().error)
        // A stray link does not widen it.
        store.link(guest.deviceId, phone.deviceId)
        assertEquals(HttpStatusCode.Forbidden, sendStatus(guest, phone.deviceId, listOf(Tab("https://a.com"))))
        val withSpace = client.post("/v1/send") {
            bearerAuth(guest.token); contentType(ContentType.Application.Json)
            setBody(SendRequest(pc.deviceId, listOf(Tab("https://a.com")), spaceId = "s1"))
        }
        assertEquals(HttpStatusCode.Forbidden, withSpace.status)
        // To its owner a guest may send files (and links, which the owner's apps never open on their own).
        val file = upload(guest, "a.txt", "hi".toByteArray())
        assertEquals(HttpStatusCode.Accepted, sendFiles(guest, pc.deviceId, listOf(file.id)).status)
        assertEquals(1, inbox(pc).size)
        assertEquals(0, inbox(phone).size)
    }
}
