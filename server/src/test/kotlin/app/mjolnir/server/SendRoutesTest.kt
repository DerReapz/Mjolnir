package app.mjolnir.server

import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent.DeliveryArrived
import app.mjolnir.protocol.Tab
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendRoutesTest {
    @Test fun sendStoresDeliveryInRecipientInbox() = relayTest {
        val pc = register("PC", DeviceKind.PC); val phone = register("Pixel 8"); pair(pc, phone)
        val tabs = listOf(Tab("https://a.com", "A"), Tab("https://b.com"))
        val sent = send(phone, pc.deviceId, tabs)
        val inbox = inbox(pc)
        assertEquals(listOf(Delivery(sent.deliveryId, Peer(phone.deviceId, "Pixel 8", DeviceKind.PHONE), tabs, clock.now)), inbox)
        assertTrue(inbox(phone).isEmpty())
    }

    @Test fun connectedRecipientGetsDeliveryEvent() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        withEvents(pc) {
            val sent = send(phone, pc.deviceId, listOf(Tab("https://a.com")))
            assertEquals(sent.deliveryId, (next() as DeliveryArrived).delivery.id)
        }
    }

    @Test fun ackRemovesDeliveryAndIsIdempotent() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val sent = send(phone, pc.deviceId, listOf(Tab("https://a.com")))
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/inbox/${sent.deliveryId}") { bearerAuth(pc.token) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/inbox/${sent.deliveryId}") { bearerAuth(pc.token) }.status)
        assertTrue(inbox(pc).isEmpty())
    }

    @Test fun cannotAckSomeoneElsesDelivery() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val sent = send(phone, pc.deviceId, listOf(Tab("https://a.com")))
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/inbox/${sent.deliveryId}") { bearerAuth(phone.token) }.status)
        assertEquals(listOf(sent.deliveryId), inbox(pc).map { it.id })
    }

    @Test fun sendToUnpairedDeviceIsForbidden() = relayTest {
        val pc = register("PC", DeviceKind.PC); val phone = register("Pixel 8")
        assertEquals(HttpStatusCode.Forbidden, sendStatus(phone, pc.deviceId, listOf(Tab("https://a.com"))))
    }

    @Test fun rejectsUnsafeEmptyOrTooManyTabs() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        assertEquals(HttpStatusCode.BadRequest, sendStatus(phone, pc.deviceId, listOf(Tab("javascript:alert(1)"))))
        assertEquals(HttpStatusCode.BadRequest, sendStatus(phone, pc.deviceId, emptyList()))
        assertEquals(HttpStatusCode.BadRequest, sendStatus(phone, pc.deviceId, List(101) { Tab("https://a.com/$it") }))
        assertEquals(HttpStatusCode.Accepted, sendStatus(phone, pc.deviceId, List(100) { Tab("https://a.com/$it") }))
    }

    @Test fun storesCleanedTabs() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        send(phone, pc.deviceId, listOf(Tab("https://a.com", "x".repeat(400)), Tab("https://a.com")))
        assertEquals(listOf(Tab("https://a.com", "x".repeat(300))), inbox(pc).single().tabs)
    }

    @Test fun deliveriesExpireAfterSevenDays() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        send(phone, pc.deviceId, listOf(Tab("https://a.com")))
        assertEquals(1, inbox(pc).size)
        clock.now += 604_800_001
        assertTrue(inbox(pc).isEmpty())
        store.purgeExpired(clock.now)
        assertTrue(inbox(pc).isEmpty())
    }

    @Test fun sendsAreRateLimited() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        repeat(60) { assertEquals(HttpStatusCode.Accepted, sendStatus(phone, pc.deviceId, listOf(Tab("https://a.com/$it")))) }
        assertEquals(HttpStatusCode.TooManyRequests, sendStatus(phone, pc.deviceId, listOf(Tab("https://a.com/x"))))
    }

    @Test fun oversizedBodyIsRejected() = relayTest {
        val (_, phone) = pairedPcAndPhone()
        val status = client.post("/v1/send") {
            bearerAuth(phone.token); contentType(ContentType.Application.Json)
            setBody("x".repeat(1_048_577))
        }.status
        assertEquals(HttpStatusCode.PayloadTooLarge, status)
    }
}
