package app.mjolnir.server

import app.mjolnir.protocol.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.*

class DeviceRoutesTest {
    @Test fun registerReturnsIdAndToken() = relayTest {
        val r = client.post("/v1/devices") { contentType(Json); setBody(RegisterRequest("Pixel 8", DeviceKind.PHONE)) }
        assertEquals(HttpStatusCode.Created, r.status)
        val body = r.body<RegisterResponse>(); assertTrue(body.deviceId.isNotBlank()); assertEquals(43, body.token.length)
    }
    @Test fun meReturnsTrimmedName() = relayTest {
        val dev = register(name = "  Pixel 8  ")
        assertEquals(Peer(dev.deviceId, "Pixel 8", DeviceKind.PHONE), client.get("/v1/devices/me") { bearerAuth(dev.token) }.body())
    }
    @Test fun rejectsBlankOrLongNames() = relayTest {
        listOf("   ", "x".repeat(65)).forEach { n ->
            assertEquals(HttpStatusCode.BadRequest, client.post("/v1/devices") { contentType(Json); setBody(RegisterRequest(n, DeviceKind.PC)) }.status)
        }
    }
    @Test fun protectedRoutesNeedAValidToken() = relayTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices/me") { bearerAuth("nope") }.status)
    }
    @Test fun tokensAreStoredOnlyAsHashes() = relayTest {
        val dev = register()
        assertNull(store.deviceByTokenHash(dev.token)); assertNotNull(store.deviceByTokenHash(sha256Hex(dev.token)))
    }
    @Test fun renameChangesName() = relayTest {
        val dev = register()
        val r = client.patch("/v1/devices/me") { bearerAuth(dev.token); contentType(Json); setBody(RenameRequest("Work phone")) }
        assertEquals("Work phone", r.body<Peer>().name)
    }
    @Test fun registrationIsRateLimitedPerIp() = relayTest {
        repeat(5) { register() }
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/v1/devices") { contentType(Json); setBody(RegisterRequest("x", DeviceKind.PC)) }.status)
    }
    @Test fun spoofedForwardedForDoesNotBypassTheRegistrationLimit() = relayTest {
        // The client controls the first X-Forwarded-For entries; only the last hop (added by the proxy) counts.
        suspend fun reg(i: Int) = client.post("/v1/devices") {
            header("X-Forwarded-For", "10.9.9.$i, 203.0.113.7"); contentType(Json); setBody(RegisterRequest("x", DeviceKind.PC))
        }.status
        repeat(5) { assertEquals(HttpStatusCode.Created, reg(it)) }
        assertEquals(HttpStatusCode.TooManyRequests, reg(99))
        // A different last hop is a different client.
        assertEquals(HttpStatusCode.Created, client.post("/v1/devices") {
            header("X-Forwarded-For", "10.9.9.1, 203.0.113.8"); contentType(Json); setBody(RegisterRequest("x", DeviceKind.PC))
        }.status)
    }
    @Test fun flyClientIpIsTheRegistrationKeyWhenPresent() = relayTest {
        suspend fun reg(i: Int) = client.post("/v1/devices") {
            header("Fly-Client-IP", "198.51.100.4"); header("X-Forwarded-For", "10.0.0.$i")
            contentType(Json); setBody(RegisterRequest("x", DeviceKind.PC))
        }.status
        repeat(5) { assertEquals(HttpStatusCode.Created, reg(it)) }
        assertEquals(HttpStatusCode.TooManyRequests, reg(99))
    }
    @Test fun healthIsOpen() = relayTest { assertEquals("ok", client.get("/health").bodyAsText()) }
}
