package app.mjolnir.server

import app.mjolnir.protocol.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import kotlin.test.*

class PairingRoutesTest {
    @Test fun codeExpiresInFiveMinutes() = relayTest {
        val pc = register("DESKTOP-4F2K", DeviceKind.PC)
        val code = client.post("/v1/pairings") { bearerAuth(pc.token) }.body<PairingCodeResponse>()
        assertEquals(clock.now + 300_000, code.expiresAtEpochMs); assertNotNull(PairingCode.normalize(code.code))
    }
    @Test fun claimNotifiesOwnerAndAcceptLinksBothWays() = relayTest {
        val pc = register("DESKTOP-4F2K", DeviceKind.PC); val phone = register("Pixel 8")
        val code = createCode(pc)
        withEvents(pc) {
            val claim = claim(phone, PairingCode.display(code).lowercase())
            assertEquals("DESKTOP-4F2K", claim.owner.name)
            val req = next() as RelayEvent.PairRequested
            assertEquals(claim.pairingId, req.pairingId); assertEquals("Pixel 8", req.from.name)
            assertEquals(HttpStatusCode.NoContent, client.post("/v1/pairings/${req.pairingId}/accept") { bearerAuth(pc.token) }.status)
            assertEquals("Pixel 8", (next() as RelayEvent.Paired).peer.name)
            assertEquals(PairingStatus(PairingState.ACCEPTED, Peer(pc.deviceId, "DESKTOP-4F2K", DeviceKind.PC)), status(phone, claim.pairingId))
        }
        assertEquals(listOf(phone.deviceId), peers(pc).map { it.deviceId }); assertEquals(listOf(pc.deviceId), peers(phone).map { it.deviceId })
    }
    @Test fun rejectLeavesDevicesUnlinked() = relayTest {
        val pc = register("PC", DeviceKind.PC); val phone = register()
        val claim = claim(phone, createCode(pc))
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/pairings/${claim.pairingId}/reject") { bearerAuth(pc.token) }.status)
        assertEquals(PairingState.REJECTED, status(phone, claim.pairingId).state)
        assertTrue(peers(pc).isEmpty()); assertTrue(peers(phone).isEmpty())
    }
    @Test fun newCodeReplacesPreviousCode() = relayTest {
        val pc = register("PC", DeviceKind.PC); val phone = register()
        val first = createCode(pc); createCode(pc)
        assertEquals(HttpStatusCode.NotFound, claimStatus(phone, first))
    }
    @Test fun expiredCodeCannotBeClaimed() = relayTest {
        val pc = register("PC", DeviceKind.PC); val phone = register()
        val code = createCode(pc)
        clock.now += 300_001
        assertEquals(HttpStatusCode.NotFound, claimStatus(phone, code))
    }
    @Test fun unansweredClaimExpires() = relayTest {
        val pc = register("PC", DeviceKind.PC); val phone = register()
        val claim = claim(phone, createCode(pc))
        clock.now += 300_001
        assertEquals(PairingState.EXPIRED, status(phone, claim.pairingId).state)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/pairings/${claim.pairingId}/accept") { bearerAuth(pc.token) }.status)
    }
    @Test fun cannotClaimOwnCode() = relayTest {
        val pc = register("PC", DeviceKind.PC)
        assertEquals(HttpStatusCode.BadRequest, claimStatus(pc, createCode(pc)))
    }
    @Test fun codeIsSingleUse() = relayTest {
        val pc = register("PC", DeviceKind.PC); val a = register("A"); val b = register("B")
        val code = createCode(pc)
        assertEquals(HttpStatusCode.OK, claimStatus(a, code))
        assertEquals(HttpStatusCode.NotFound, claimStatus(b, code))
    }
    @Test fun onlyOwnerCanAcceptOrReject() = relayTest {
        val pc = register("PC", DeviceKind.PC); val phone = register()
        val claim = claim(phone, createCode(pc))
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/pairings/${claim.pairingId}/accept") { bearerAuth(phone.token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/pairings/${claim.pairingId}/reject") { bearerAuth(phone.token) }.status)
    }
    @Test fun strangersCannotReadPairingStatus() = relayTest {
        val pc = register("PC", DeviceKind.PC); val phone = register(); val other = register("Other")
        val claim = claim(phone, createCode(pc))
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/pairings/${claim.pairingId}") { bearerAuth(other.token) }.status)
    }
    @Test fun claimsAreRateLimited() = relayTest {
        val phone = register()
        repeat(10) { assertEquals(HttpStatusCode.NotFound, claimStatus(phone, "ZZZZZZ")) }
        assertEquals(HttpStatusCode.TooManyRequests, claimStatus(phone, "ZZZZZZ"))
    }
    @Test fun unpairRemovesLinkBothSidesAndNotifiesTheOther() = relayTest {
        val pc = register("PC", DeviceKind.PC); val phone = register(); pair(pc, phone)
        withEvents(pc) {
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/peers/${pc.deviceId}") { bearerAuth(phone.token) }.status)
            assertEquals(RelayEvent.Unpaired(phone.deviceId), next())
        }
        assertTrue(peers(pc).isEmpty()); assertTrue(peers(phone).isEmpty())
    }
}
