package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.MjolnirJson
import app.mjolnir.protocol.RegisterRequest
import app.mjolnir.protocol.RegisterResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication

class FakeClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs(): Long = now
}

class RelayTestScope(
    val client: HttpClient,
    val deps: RelayDeps,
    val clock: FakeClock,
) {
    val store: Store get() = deps.store

    suspend fun register(name: String = "Pixel 8", kind: DeviceKind = DeviceKind.PHONE): RegisterResponse =
        client.post("/v1/devices") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(name, kind))
        }.body()
}

fun relayTest(
    clock: FakeClock = FakeClock(),
    limits: RelayLimits = RelayLimits(),
    block: suspend RelayTestScope.() -> Unit,
) = testApplication {
    val deps = RelayDeps(store = Store("jdbc:sqlite::memory:"), clock = clock, limits = limits)
    application { relayModule(deps) }
    val client = createClient {
        install(ContentNegotiation) { json(MjolnirJson) }
        install(WebSockets)
    }
    RelayTestScope(client, deps, clock).block()
}
