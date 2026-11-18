package app.mjolnir.server

import app.mjolnir.protocol.Tab
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingPush(var fail: Boolean = false) : PushSender {
    val pinged = CopyOnWriteArrayList<String>()
    override suspend fun ping(pushToken: String) {
        pinged += pushToken
        if (fail) error("fcm down")
    }
}

private suspend fun eventually(block: () -> Unit) {
    withTimeout(5_000) {
        while (true) {
            try { block(); return@withTimeout } catch (e: AssertionError) { delay(20) }
        }
    }
}

class PushTest {
    @Test fun sendPingsRecipientPushToken() { val push = RecordingPush(); relayTest(push = push) {
        val (pc, phone) = pairedPcAndPhone(); setPushToken(phone, "fcm-abc")
        send(pc, phone.deviceId, listOf(Tab("https://a.com")))
        eventually { assertEquals(listOf("fcm-abc"), push.pinged) }
    } }

    @Test fun noPushTokenMeansNoPing() { val push = RecordingPush(); relayTest(push = push) {
        val (pc, phone) = pairedPcAndPhone()
        send(pc, phone.deviceId, listOf(Tab("https://a.com")))
        delay(200)
        assertTrue(push.pinged.isEmpty())
    } }

    @Test fun pushFailureDoesNotFailSend() { val push = RecordingPush(fail = true); relayTest(push = push) {
        val (pc, phone) = pairedPcAndPhone(); setPushToken(phone, "fcm-abc")
        send(pc, phone.deviceId, listOf(Tab("https://a.com")))
        eventually { assertEquals(1, push.pinged.size) }
        assertEquals(1, inbox(phone).size)
    } }

    @Test fun blankPushTokenIsRejected() { relayTest {
        val phone = register()
        assertEquals(HttpStatusCode.BadRequest, setPushToken(phone, " "))
        assertEquals(HttpStatusCode.NoContent, setPushToken(phone, "ok"))
    } }
}
