package app.mjolnir.client

import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RelayHealthTest {
    private fun health(body: String, delayMs: Long = 0, timeoutMs: Long = 5_000, block: (RelayCheck) -> Unit) = testApplication {
        application { routing { get("/health") { if (delayMs > 0) delay(delayMs); call.respondText(body) } } }
        block(checkRelay("http://localhost", timeoutMs, client))
    }

    @Test fun okReturnsMillis() = health("ok") { r -> assertTrue(assertIs<RelayCheck.Ok>(r).millis >= 0) }

    @Test fun wrongBodyFails() = health("nope") { assertIs<RelayCheck.Failed>(it) }

    @Test fun timeoutFailsWithin5s() {
        val start = System.nanoTime()
        health("ok", delayMs = 10_000, timeoutMs = 200) { assertIs<RelayCheck.Failed>(it) }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
    }
}
