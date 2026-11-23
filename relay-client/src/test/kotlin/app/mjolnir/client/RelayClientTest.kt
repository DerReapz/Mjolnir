package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.PairingCode
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.Tab
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayClientTest {
    @Test fun phoneThrowsToPcRoundTrip() = clientTest {
        val pc = newDevice("DESKTOP-4F2K", DeviceKind.PC); val phone = newDevice("Pixel 8", DeviceKind.PHONE)
        val code = pc.api.createPairingCode().code
        val claim = phone.api.claimPairingCode(PairingCode.display(code).lowercase())
        assertEquals("DESKTOP-4F2K", claim.owner.name)
        pc.api.acceptPairing(claim.pairingId)
        assertEquals(PairingState.ACCEPTED, phone.api.pairingStatus(claim.pairingId).state)
        phone.api.send(pc.id, listOf(Tab("https://a.com", "A")))
        val d = pc.api.inbox().single(); assertEquals("Pixel 8", d.from.name)
        pc.api.ack(d.id); assertTrue(pc.api.inbox().isEmpty())
    }

    @Test fun mapsHttpErrorsToKinds() = clientTest {
        val phone = newDevice("P", DeviceKind.PHONE); val pc = newDevice("C", DeviceKind.PC)
        assertKind(RelayErrorKind.UNAUTHORIZED) { anonymousApi.me() }
        assertKind(RelayErrorKind.NOT_PAIRED) { phone.api.send(pc.id, listOf(Tab("https://a.com"))) }
        assertKind(RelayErrorKind.NOT_FOUND) { phone.api.claimPairingCode("ZZZZZZ") }
    }

    @Test fun chatFileRequestPointsAtTheRelayWithTheBearer() {
        var token: String? = "tok-9"
        val client = RelayClient.create("https://relay.example/", "1.0.0") { token }
        client.use {
            assertEquals(FileRequest("https://relay.example/v1/files/f1", mapOf("Authorization" to "Bearer tok-9")), it.chatFileRequest("f1"))
            token = null // read live: no token, no header
            assertEquals(FileRequest("https://relay.example/v1/files/f1", emptyMap()), it.chatFileRequest("f1"))
        }
    }

    @Test fun unreachableRelayIsNetworkError() = runTest {
        assertKind(RelayErrorKind.NETWORK) { RelayClient.create("http://127.0.0.1:1", "1.0.0") { null }.register("x", DeviceKind.PC) }
    }
}
