package app.mjolnir.server

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class HeartbeatTest {
    @Test fun openSocketReceivesHeartbeatFrames() = relayTest(heartbeatMs = 50) {
        val pc = register("PC", app.mjolnir.protocol.DeviceKind.PC)
        client.webSocket("/v1/ws", request = { bearerAuth(pc.token) }) {
            repeat(2) {
                val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
                assertEquals("""{"type":"heartbeat"}""", frame.readText())
            }
        }
    }
}
