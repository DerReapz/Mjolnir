package app.mjolnir.android

import android.app.Notification
import app.mjolnir.client.IncomingMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NotificationsTest {
    private val plain = IncomingMessage("c1", "General", "sam", "Sam", "hi")
    private val mention = plain.copy(text = "hi @Me", mentionedMe = true)

    @Test fun mentionNotificationHighPriority() {
        val m = chatNotificationSpec(mention, "Sam")
        assertEquals(CHANNEL_MENTIONS, m.channel)
        assertEquals(Notification.PRIORITY_HIGH, m.priority)
        assertEquals("Sam mentioned you in General: hi @Me", m.text)
        val p = chatNotificationSpec(plain, "Sam")
        assertEquals(CHANNEL_CHAT, p.channel)
        assertEquals(Notification.PRIORITY_DEFAULT, p.priority)
        assertEquals("Sam in General: hi", p.text)
        // A mention is its own notification: it neither replaces nor is replaced by the chat's ordinary one.
        assertNotEquals(m.id, p.id)
        assertEquals(m.id, chatNotificationSpec(mention.copy(text = "again @Me"), "Sam").id)
    }
}
