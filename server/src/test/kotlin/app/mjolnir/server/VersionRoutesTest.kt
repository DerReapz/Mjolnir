package app.mjolnir.server

import app.mjolnir.protocol.APP_VERSION_HEADER
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.RELAY_VERSION_HEADER
import app.mjolnir.protocol.RelayEvent
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionRoutesTest {
    @Test fun healthStillOkWithHeader() = relayTest(version = "1.0.7") {
        val r = client.get("/health") { header(APP_VERSION_HEADER, "1.0.1") }
        assertEquals("ok", r.bodyAsText())
        assertEquals("1.0.7", r.headers[RELAY_VERSION_HEADER])
    }

    @Test fun versionEndpoint() = relayTest(version = "1.0.7") {
        val r = client.get("/version")
        assertEquals("""{"version":"1.0.7"}""", r.bodyAsText())
        assertEquals("1.0.7", r.headers[RELAY_VERSION_HEADER])
    }

    @Test fun olderAppGetsUpdateAvailableOnConnect() = relayTest(version = "1.0.10") {
        val pc = register("PC", DeviceKind.PC)
        client.webSocket("/v1/ws", request = { bearerAuth(pc.token); header(APP_VERSION_HEADER, "1.0.9") }) {
            val event = EventReader(incoming).next()
            assertEquals(RelayEvent.UpdateAvailable("1.0.10"), event)
        }
    }

    @Test fun sameOrNewerAppGetsNothing() = relayTest(version = "1.0.10", heartbeatMs = 60_000) {
        val pc = register("PC", DeviceKind.PC)
        for (v in listOf("1.0.10", "1.0.11")) {
            client.webSocket("/v1/ws", request = { bearerAuth(pc.token); header(APP_VERSION_HEADER, v) }) {
                assertNull(withTimeoutOrNull(300) { incoming.receive() as Frame.Text }?.readText())
            }
        }
    }

    @Test fun missingAppHeaderGetsNothing() = relayTest(version = "1.0.10", heartbeatMs = 60_000) {
        val pc = register("PC", DeviceKind.PC)
        client.webSocket("/v1/ws", request = { bearerAuth(pc.token) }) {
            assertNull(withTimeoutOrNull(300) { incoming.receive() as Frame.Text }?.readText())
        }
    }
}
