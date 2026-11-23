package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.server.RelayDeps
import app.mjolnir.server.Store
import app.mjolnir.server.relayModule
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.fail

class TestDevice(val id: String, val api: RelayClient)

class ClientTestScope(private val builder: ApplicationTestBuilder, val deps: RelayDeps, scope: CoroutineScope) : CoroutineScope by scope {
    private fun http(): HttpClient = builder.createClient { installMjolnirDefaults() }

    // No transfer socket timeout here: Ktor 3.3.0's test host races on it (its socket-timeout watchdog reads a
    // closed response channel and fails the test with ClosedWriteChannelException). Production keeps the timeout.
    private fun client(token: () -> String?) = RelayClient(http(), "http://localhost", "1.0.0", transferSocketTimeoutMs = null, token = token)

    val anonymousApi: RelayClient = client { null }

    fun apiWithToken(token: String): RelayClient = client { token }

    suspend fun newDevice(name: String, kind: DeviceKind): TestDevice {
        val reg = anonymousApi.register(name, kind)
        return TestDevice(reg.deviceId, client { reg.token })
    }
}

/** A registered PC and phone that are already paired: (pc, phone). */
suspend fun ClientTestScope.pairedDevices(): Pair<TestDevice, TestDevice> {
    val pc = newDevice("DESKTOP-4F2K", DeviceKind.PC)
    val phone = newDevice("Pixel 8", DeviceKind.PHONE)
    val claim = phone.api.claimPairingCode(pc.api.createPairingCode().code)
    pc.api.acceptPairing(claim.pairingId)
    return pc to phone
}

suspend fun ClientTestScope.awaitHubConnected(deviceId: String) {
    withTimeout(5_000) { while (!deps.hub.isConnected(deviceId)) delay(10) }
}

fun fakeWsTest(block: suspend (RelayClient) -> Unit) = testApplication {
    application {
        install(WebSockets)
        routing {
            webSocket("/v1/ws") {
                send(Frame.Text("""{"type":"future_thing"}"""))
                send(Frame.Text("""{"type":"unpaired","deviceId":"x"}"""))
                close()
            }
        }
    }
    val http = createClient { installMjolnirDefaults() }
    RelayClient(http, "http://localhost", "1.0.0", reconnectDelayMs = { 10L }) { "t" }.use { block(it) }
}

fun heartbeatOnlyWsTest(block: suspend (RelayClient) -> Unit) = testApplication {
    application {
        install(WebSockets)
        routing {
            webSocket("/v1/ws") {
                while (true) { send(Frame.Text("""{"type":"heartbeat"}""")); delay(50) }
            }
        }
    }
    val http = createClient { installMjolnirDefaults() }
    RelayClient(http, "http://localhost", "1.0.0", reconnectDelayMs = { 10L }, idleTimeoutMs = 200) { "t" }.use { block(it) }
}

fun silentWsTest(block: suspend (RelayClient) -> Unit) = testApplication {
    application {
        install(WebSockets)
        routing {
            webSocket("/v1/ws") {
                send(Frame.Text("""{"type":"unpaired","deviceId":"x"}"""))
                awaitCancellation()
            }
        }
    }
    val http = createClient { installMjolnirDefaults() }
    RelayClient(http, "http://localhost", "1.0.0", reconnectDelayMs = { 10L }, idleTimeoutMs = 200) { "t" }.use { block(it) }
}

fun clientTest(
    deps: RelayDeps = RelayDeps(store = Store("jdbc:sqlite::memory:")),
    block: suspend ClientTestScope.() -> Unit,
) = testApplication {
    application { relayModule(deps) }
    coroutineScope { ClientTestScope(this@testApplication, deps, this).block() }
}

suspend fun assertKind(kind: RelayErrorKind, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: RelayException) {
        assertEquals(kind, e.kind, "unexpected error kind (${e.message})")
        return
    }
    fail("expected RelayException of kind $kind")
}
