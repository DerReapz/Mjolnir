package app.mjolnir.client

import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesTest {
    @Test fun messagesPluralize() {
        assertEquals("Caught 1 tab from Pixel 8", Messages.caught(1, 0, "Pixel 8"))
        assertEquals("Thrown 3 tabs to DESKTOP-4F2K", Messages.thrown(3, 0, "DESKTOP-4F2K"))
        assertEquals("Caught 2 tabs and 1 file from Pixel 8", Messages.caught(2, 1, "Pixel 8"))
        assertEquals("Sent 2 files to Pixel 8", Messages.sent(0, 2, "Pixel 8"))
        assertEquals("80 MB", Messages.size(83_886_080)); assertEquals("1.2 GB", Messages.size(1_288_490_189))
        assertEquals("Waiting for Wi-Fi: 1 file (80 MB) from DESKTOP-4F2K", Messages.waitingToReceive(1, 83_886_080, "DESKTOP-4F2K"))
        assertEquals("Not thrown: no connection. Your tabs are still here.",
            Messages.notThrown(Messages.failureReason(RelayErrorKind.NETWORK, "PC")))
    }

    @Test fun outgoingMessagesCarryTheSpace() {
        assertEquals("Sent 2 files to Sam · Friends", Messages.sent(0, 2, "Sam", space = "Friends"))
        assertEquals("Waiting for Wi-Fi to throw 1 file (80 MB) to Sam · Friends", Messages.waitingToThrow(1, 83_886_080, "Sam", space = "Friends"))
        assertEquals("Waiting for Wi-Fi to throw 1 file (80 MB) to Sam", Messages.waitingToThrow(1, 83_886_080, "Sam"))
    }

    @Test fun caughtWithSpace() {
        assertEquals("Caught 1 tab from Sam · Friends", Messages.caught(1, 0, "Sam", space = "Friends"))
        assertEquals("Thrown 2 tabs to Sam · Friends", Messages.thrown(2, 0, "Sam", space = "Friends"))
        assertEquals("Caught 1 tab from Sam", Messages.caught(1, 0, "Sam", space = null))
    }

    @Test fun chatCopyIsExact() {
        assertEquals("Sam in General: hi", Messages.chatNotification("Sam", "General", "hi"))
        assertEquals("Sam wants to add Ann", Messages.inviteApproval("Sam", "Ann"))
        assertEquals("You're invited to General", Messages.invitedTo("General"))
        assertEquals("Message General", Messages.messageChat("General"))
        assertEquals("Deleted by the space admin", Messages.DELETED_BY_ADMIN)
    }
}
