package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.server.RelayDeps
import app.mjolnir.server.Store
import app.mjolnir.server.relayModule
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.fail

class TestDevice(val id: String, val api: RelayClient)

class ClientTestScope(private val builder: ApplicationTestBuilder, val deps: RelayDeps) {
    private fun http(): HttpClient = builder.createClient { installMjolnirDefaults() }

    val anonymousApi: RelayClient = RelayClient(http(), "http://localhost") { null }

    suspend fun newDevice(name: String, kind: DeviceKind): TestDevice {
        val reg = anonymousApi.register(name, kind)
        return TestDevice(reg.deviceId, RelayClient(http(), "http://localhost") { reg.token })
    }
}

fun clientTest(block: suspend ClientTestScope.() -> Unit) = testApplication {
    val deps = RelayDeps(store = Store("jdbc:sqlite::memory:"))
    application { relayModule(deps) }
    ClientTestScope(this, deps).block()
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
