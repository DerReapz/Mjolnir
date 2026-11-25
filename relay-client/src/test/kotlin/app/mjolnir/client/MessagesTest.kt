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

    @Test fun mentionNotificationCopy() {
        assertEquals("Sam mentioned you in General: hi @Ann", Messages.mentionNotification("Sam", "General", "hi @Ann"))
    }

    @Test fun receiptLineEveryoneVsNames() {
        assertEquals("Read by everyone", Messages.receiptLine(listOf("Ann", "Bo"), emptyList(), everyone = true))
        assertEquals("Read by Ann, Bo · Seen by Cy", Messages.receiptLine(listOf("Ann", "Bo"), listOf("Cy"), everyone = false))
        assertEquals("Read by Ann", Messages.receiptLine(listOf("Ann"), emptyList(), everyone = false))
        assertEquals("Seen by Cy, Di", Messages.receiptLine(emptyList(), listOf("Cy", "Di"), everyone = false))
        assertEquals("", Messages.receiptLine(emptyList(), emptyList(), everyone = false))
        assertEquals("Info", Messages.INFO)
    }

    @Test fun mediaNotSentReadsAsOneSentence() {
        val unreadable = RelayException(RelayErrorKind.INVALID, "short", userMessage = Messages.unreadable("x.jpg"))
        assertEquals("Not sent: couldn't read x.jpg.", Messages.mediaNotSent(unreadable))
        assertEquals("Not sent: the relay is out of space.", Messages.mediaNotSent(RelayException(RelayErrorKind.STORAGE_FULL, "relay_full")))
    }

    @Test fun mediaSendCopy() {
        assertEquals("Cancel", Messages.CANCEL)
        assertEquals("clip.mov is too big for the relay", Messages.tooBigForRelay("clip.mov"))
    }

    @Test fun videoTimeFormats() {
        assertEquals("0:00", Messages.duration(0))
        assertEquals("0:42", Messages.duration(42_999))
        assertEquals("12:05", Messages.duration(725_000))
        assertEquals("1:02:03", Messages.duration(3_723_000))
        assertEquals("0:00", Messages.duration(-5))
        assertEquals("0:16 / 0:42", Messages.videoTime(16_000, 42_000))
        assertEquals("1:02:03 / 1:10:00", Messages.videoTime(3_723_000, 4_200_000))
        assertEquals("Can't play this video here", Messages.CANT_PLAY_VIDEO)
        assertEquals("Open in player", Messages.OPEN_IN_PLAYER)
        assertEquals("Video unavailable", Messages.VIDEO_UNAVAILABLE)
    }

    @Test fun videoPreviewAddsItsLength() {
        val fred = app.mjolnir.protocol.Peer("f", "Fred", app.mjolnir.protocol.DeviceKind.PHONE)
        fun video(durationMs: Long?) = app.mjolnir.protocol.ChatMessage(
            "m", "c", fred, 1, 0,
            attachments = listOf(app.mjolnir.protocol.Attachment("v", app.mjolnir.protocol.AttachmentKind.VIDEO, "a.mp4", 9, "video/mp4", durationMs = durationMs)),
        )
        assertEquals("Video · 0:42", Messages.messagePreview(video(42_000)))
        assertEquals("Video", Messages.messagePreview(video(null)))
        assertEquals("Video", Messages.messagePreview(video(0))) // a bad length shows none
    }

    /** relayUpdateReason leaves exactly the kinds with their own failureReason alone (UP-R5 consistency). */
    @Test fun specificFailureKindsAreTheKindsWithTheirOwnReason() {
        val generic = Messages.failureReason(RelayErrorKind.SERVER, "PC")
        val ownReason = RelayErrorKind.entries.filter { Messages.failureReason(it, "PC") != generic }.toSet()
        assertEquals(ownReason, Messages.SPECIFIC_FAILURE_KINDS)
        for (kind in RelayErrorKind.entries) {
            val older = RelayException(kind, kind.name, fromOlderRelay = true)
            val expected = if (kind in ownReason) Messages.failureReason(kind, "PC") else "the relay needs updating"
            assertEquals(expected, Messages.failureReason(older, "PC"), kind.name)
        }
    }

    @Test fun lastSeenReadsNaturally() {
        val now = java.time.LocalDateTime.of(2026, 11, 24, 12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        fun seen(ago: Long?) = Messages.lastSeen(now, ago?.let { now - it }, java.time.ZoneOffset.UTC)
        assertEquals("Last seen just now", seen(30_000))
        assertEquals("Last seen 5m ago", seen(5 * 60_000))
        assertEquals("Last seen 2h ago", seen(2 * 3_600_000))
        assertEquals("Last seen yesterday", seen(30 * 3_600_000))
        assertEquals("", seen(null))
        val nov12 = java.time.LocalDateTime.of(2026, 11, 12, 9, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertEquals("Last seen 12 Nov", Messages.lastSeen(now, nov12, java.time.ZoneOffset.UTC))
    }

    @Test fun channelCopy() {
        assertEquals("Everyone in Crew can see this channel.", Messages.channelHint("Crew"))
        assertEquals("Delete #games? Everyone in Crew loses its messages.", Messages.deleteChannelConfirm("games", "Crew"))
        assertEquals("Ann, Bob", Messages.seenBySummary(listOf("Ann", "Bob")))
        assertEquals("A, B, C, D, E", Messages.seenBySummary(listOf("A", "B", "C", "D", "E")))
        assertEquals("Seen by 6", Messages.seenBySummary(listOf("A", "B", "C", "D", "E", "F")))
        assertEquals(Messages.NOT_FOR_OPEN_CHANNELS, Messages.chatError(RelayException(RelayErrorKind.ALREADY_SENT, "not_for_open_channels")))
        assertEquals("Open channels can't be joined or left — everyone in the space is in them.", Messages.NOT_FOR_OPEN_CHANNELS)
    }

    @Test fun voiceCopyIsExact() {
        assertEquals("Voice", Messages.VOICE); assertEquals("Join voice", Messages.JOIN_VOICE); assertEquals("Leave", Messages.LEAVE_VOICE)
        assertEquals("Mute", Messages.MUTE); assertEquals("Unmute", Messages.UNMUTE)
        assertEquals("Push to talk", Messages.PUSH_TO_TALK); assertEquals("Open mic", Messages.OPEN_MIC)
        assertEquals("Hold ` to talk", Messages.HOLD_TO_TALK)
        assertEquals("New voice room", Messages.NEW_VOICE_ROOM); assertEquals("Voice room name", Messages.VOICE_ROOM_NAME)
        assertEquals("Reconnecting…", Messages.RECONNECTING)
        assertEquals("Use headphones to avoid echo", Messages.USE_HEADPHONES)
        assertEquals("Mjolnir needs the microphone for voice rooms.", Messages.MIC_PERMISSION)
        assertEquals("Open settings", Messages.OPEN_SETTINGS)
        assertEquals("In voice: Lounge", Messages.inVoice("Lounge"))
        assertEquals("Mjolnir voice — Lounge", Messages.voiceNotificationTitle("Lounge"))
        assertEquals("Delete Lounge? Anyone in it is disconnected.", Messages.deleteVoiceRoomConfirm("Lounge"))
        assertEquals("Lounge is full (8/8)", Messages.voiceFull("Lounge", 8, 8))
    }

    @Test fun voiceEndedReasons() {
        assertEquals("You left Lounge (connection lost)", Messages.voiceEnded("connection_lost", "Lounge"))
        assertEquals("Lounge is full (8/8)", Messages.voiceEnded("room_full", "Lounge", n = 8, max = 8))
        assertEquals("Lounge was deleted", Messages.voiceEnded("room_deleted", "Lounge"))
        assertEquals("You were removed from Lounge", Messages.voiceEnded("removed", "Lounge"))
        assertEquals("You joined Lounge somewhere else", Messages.voiceEnded("replaced", "Lounge"))
        assertEquals("Lounge is gone", Messages.voiceEnded("not_found", "Lounge"))
        assertEquals(null, Messages.voiceEnded(null, "Lounge"), "a deliberate leave says nothing")
    }
}
