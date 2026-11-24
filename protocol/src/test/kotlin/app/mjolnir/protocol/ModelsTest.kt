package app.mjolnir.protocol

import kotlin.test.*

class ModelsTest {
    @Test fun relayEventsUseTypeDiscriminator() {
        val json = MjolnirJson.encodeToString(RelayEvent.serializer(), RelayEvent.Unpaired("dev-1"))
        assertEquals("""{"type":"unpaired","deviceId":"dev-1"}""", json)
        assertEquals(RelayEvent.Unpaired("dev-1"), MjolnirJson.decodeFromString(RelayEvent.serializer(), json))
    }
    @Test fun unknownFieldsAreIgnored() = assertEquals(Peer("d", "n", DeviceKind.PC),
        MjolnirJson.decodeFromString<Peer>("""{"deviceId":"d","name":"n","kind":"PC","extra":1}"""))
}

class SpacesModelsTest {
    @Test fun oldPeerJsonStillDecodes() {
        val p = MjolnirJson.decodeFromString<Peer>("""{"deviceId":"d","name":"n","kind":"PC"}""")
        assertEquals(null, p.space)
    }
    @Test fun spaceJsonWithoutOrWithOldAdminFieldsDecodes() {
        val bare = MjolnirJson.decodeFromString<Space>("""{"id":"s","name":"S","adminId":"a","members":[]}""")
        assertFalse(bare.adminSilent)
        val old = MjolnirJson.decodeFromString<Space>("""{"id":"s","name":"S","adminId":"a","members":[],"adminLastSeenEpochMs":5}""")
        assertFalse(old.adminSilent)
        assertTrue(MjolnirJson.decodeFromString<Space>("""{"id":"s","name":"S","adminId":"a","members":[],"adminSilent":true}""").adminSilent)
    }
    @Test fun oldClaimAndSendJsonStillDecode() {
        assertEquals(CodeKind.PAIRING, MjolnirJson.decodeFromString<ClaimRequest>("""{"code":"x"}""").expect)
        assertEquals(null, MjolnirJson.decodeFromString<SendRequest>("""{"to":"d"}""").spaceId)
    }
    @Test fun spaceEventsUseTypeDiscriminator() {
        val json = MjolnirJson.encodeToString(RelayEvent.serializer(), RelayEvent.SpaceChanged("s1"))
        assertEquals("""{"type":"space_changed","spaceId":"s1"}""", json)
        val del = RelayEvent.SpaceDeleted(SpaceRef("s1", "Home"))
        assertEquals(del, MjolnirJson.decodeFromString(RelayEvent.serializer(), MjolnirJson.encodeToString(RelayEvent.serializer(), del)))
    }
}

class GuestRoomModelsTest {
    private fun roundTrip(e: RelayEvent) =
        assertEquals(e, MjolnirJson.decodeFromString(RelayEvent.serializer(), MjolnirJson.encodeToString(RelayEvent.serializer(), e)))

    @Test fun guestKindAndRoomEventsUseTheirSerialNames() {
        assertEquals("\"GUEST\"", MjolnirJson.encodeToString(DeviceKind.serializer(), DeviceKind.GUEST))
        val guest = Peer("g", "Guest", DeviceKind.GUEST)
        assertEquals(
            """{"type":"guest_joined","roomId":"r","guest":{"deviceId":"g","name":"Guest","kind":"GUEST","space":null,"online":false,"lastSeenMs":null}}""",
            MjolnirJson.encodeToString(RelayEvent.serializer(), RelayEvent.GuestJoined("r", guest)),
        )
        assertEquals("""{"type":"room_ended","roomId":"r"}""", MjolnirJson.encodeToString(RelayEvent.serializer(), RelayEvent.RoomEnded("r")))
        assertEquals(
            """{"type":"downloaded","deliveryId":"d","fileIds":["f1","f2"]}""",
            MjolnirJson.encodeToString(RelayEvent.serializer(), RelayEvent.Downloaded("d", listOf("f1", "f2"))),
        )
        roundTrip(RelayEvent.GuestJoined("r", guest)); roundTrip(RelayEvent.RoomEnded("r")); roundTrip(RelayEvent.Downloaded("d", listOf("f")))
    }

    @Test fun roomAndTicketModelsRoundTrip() {
        val join = GuestJoinResponse("g", "t", "r", Peer("o", "PC", DeviceKind.PC), 5L, 100)
        assertEquals(join, MjolnirJson.decodeFromString<GuestJoinResponse>(MjolnirJson.encodeToString(GuestJoinResponse.serializer(), join)))
        assertEquals(TicketRequest(fileId = "f"), MjolnirJson.decodeFromString<TicketRequest>("""{"fileId":"f"}"""))
        assertEquals(TicketRequest(deliveryId = "d", folder = "Photos"), MjolnirJson.decodeFromString<TicketRequest>("""{"deliveryId":"d","folder":"Photos"}"""))
        assertEquals(RoomStatus("r", 2L, 1L, null), MjolnirJson.decodeFromString<RoomStatus>("""{"roomId":"r","expiresAtEpochMs":2,"serverNowEpochMs":1,"guest":null}"""))
        assertEquals(RoomResponse("r", "s", 3L), MjolnirJson.decodeFromString<RoomResponse>("""{"roomId":"r","secret":"s","expiresAtEpochMs":3}"""))
        assertEquals(GuestJoinRequest("s"), MjolnirJson.decodeFromString<GuestJoinRequest>("""{"secret":"s"}"""))
        assertEquals(TicketResponse("x"), MjolnirJson.decodeFromString<TicketResponse>("""{"ticket":"x"}"""))
    }

    @Test fun unknownDeviceKindDecodesAsPc() {
        assertEquals(Peer("d", "n", DeviceKind.PC), MjolnirJson.decodeFromString<Peer>("""{"deviceId":"d","name":"n","kind":"WATCH"}"""))
        assertEquals(DeviceKind.GUEST, MjolnirJson.decodeFromString<RegisterRequest>("""{"name":"n","kind":"GUEST"}""").kind)
    }

    @Test fun roomStatusDownloadedIdsDefaultToEmpty() {
        assertEquals(emptyList(), MjolnirJson.decodeFromString<RoomStatus>("""{"roomId":"r","expiresAtEpochMs":2,"serverNowEpochMs":1,"guest":null}""").downloadedFileIds)
        val s = RoomStatus("r", 2L, 1L, null, listOf("f"))
        assertEquals(s, MjolnirJson.decodeFromString<RoomStatus>(MjolnirJson.encodeToString(RoomStatus.serializer(), s)))
    }
}

class ChatModelsTest {
    private fun roundTrip(e: RelayEvent) =
        assertEquals(e, MjolnirJson.decodeFromString(RelayEvent.serializer(), MjolnirJson.encodeToString(RelayEvent.serializer(), e)))

    @Test fun chatEventsUseTypeDiscriminator() {
        assertEquals(
            """{"type":"chat_changed","chatId":"c","rev":3,"mentioned":false,"receipts":[]}""",
            MjolnirJson.encodeToString(RelayEvent.serializer(), RelayEvent.ChatChanged("c", 3)),
        )
        // An event from a relay before mentions and receipts still reads, as does a sync without receipts.
        assertEquals(RelayEvent.ChatChanged("c", 3), MjolnirJson.decodeFromString(RelayEvent.serializer(), """{"type":"chat_changed","chatId":"c","rev":3}"""))
        assertEquals(emptyList(), MjolnirJson.decodeFromString(ChatSync.serializer(), """{"chatId":"c","rev":1,"ownerId":"o","members":[],"messages":[],"hasMore":false}""").receipts)
        assertEquals(PostMessageRequest("hi", listOf("d1")), MjolnirJson.decodeFromString(PostMessageRequest.serializer(), """{"text":"hi","mentions":["d1"]}"""))
        assertEquals(PostMessageRequest("hi"), MjolnirJson.decodeFromString(PostMessageRequest.serializer(), """{"text":"hi"}"""))
        // Media-only requests and a message from before attachments both read.
        assertEquals(PostMessageRequest(null, emptyList(), emptyList()), MjolnirJson.decodeFromString(PostMessageRequest.serializer(), "{}"))
        val att = Attachment("f", AttachmentKind.GIF, "a.gif", 3, "image/gif", 1, 2, null, "t", "dir/a.gif")
        assertEquals(PostMessageRequest(null, emptyList(), listOf(att)), MjolnirJson.decodeFromString(PostMessageRequest.serializer(), MjolnirJson.encodeToString(PostMessageRequest.serializer(), PostMessageRequest(null, emptyList(), listOf(att)))))
        assertEquals(emptyList(), MjolnirJson.decodeFromString(ChatMessage.serializer(), """{"id":"m","chatId":"c","author":{"deviceId":"a","name":"A","kind":"PC"},"rev":1,"sentAtEpochMs":1}""").attachments)
        assertEquals("""{"type":"chat_deleted","chatId":"c"}""", MjolnirJson.encodeToString(RelayEvent.serializer(), RelayEvent.ChatDeleted("c")))
        assertEquals("""{"type":"chat_invites_changed"}""", MjolnirJson.encodeToString(RelayEvent.serializer(), RelayEvent.ChatInvitesChanged))
        roundTrip(RelayEvent.ChatChanged("c", 3)); roundTrip(RelayEvent.ChatDeleted("c")); roundTrip(RelayEvent.ChatInvitesChanged)
    }

    @Test fun oldEventJsonStillDecodes() {
        assertEquals(RelayEvent.Unpaired("d"), MjolnirJson.decodeFromString(RelayEvent.serializer(), """{"type":"unpaired","deviceId":"d"}"""))
        // A new field on a known event and an unknown event type: the client's lenient decoding must not choke on the former
        // and must be able to skip the latter.
        assertEquals(RelayEvent.SpaceChanged("s"), MjolnirJson.decodeFromString(RelayEvent.serializer(), """{"type":"space_changed","spaceId":"s","extra":1}"""))
        val unknown = runCatching { MjolnirJson.decodeFromString(RelayEvent.serializer(), """{"type":"from_the_future","x":1}""") }
        assertTrue(unknown.isFailure) // callers skip undecodable events (see RelayClient)
    }

    @Test fun messageDefaults() {
        val m = MjolnirJson.decodeFromString<ChatMessage>("""{"id":"m","chatId":"c","author":{"deviceId":"d","name":"n","kind":"PC"},"rev":1,"sentAtEpochMs":2}""")
        assertFalse(m.deleted); assertFalse(m.deletedByAdmin); assertEquals(null, m.text); assertEquals(null, m.editedAtEpochMs)
    }
}

class ChatEventModelsTest {
    @Test fun chatEventsRoundTripAndDefaultToNull() {
        val peer = Peer("d", "n", DeviceKind.PC)
        val m = ChatMessage("m", "c", peer, 2, 3, event = ChatEvent.MemberRemoved("x", true))
        assertEquals(m, MjolnirJson.decodeFromString<ChatMessage>(MjolnirJson.encodeToString(ChatMessage.serializer(), m)))
        listOf(ChatEvent.MemberLeft("x"), ChatEvent.MemberJoined("x"), ChatEvent.OwnerChanged("x"), ChatEvent.Renamed("N")).forEach {
            assertEquals(it, MjolnirJson.decodeFromString(ChatEvent.serializer(), MjolnirJson.encodeToString(ChatEvent.serializer(), it)))
        }
        assertEquals("""{"type":"member_removed","deviceId":"x","byAdmin":true}""", MjolnirJson.encodeToString(ChatEvent.serializer(), ChatEvent.MemberRemoved("x", true)))
        assertEquals(null, MjolnirJson.decodeFromString<ChatMessage>("""{"id":"m","chatId":"c","author":{"deviceId":"d","name":"n","kind":"PC"},"rev":1,"sentAtEpochMs":2}""").event)
    }
}

class OpenChannelModelsTest {
    @Test fun unknownChatKindDecodesAsPrivate() {
        assertEquals(ChatKind.PRIVATE, MjolnirJson.decodeFromString(ChatKind.serializer(), "\"VOICE\""))
        assertEquals(ChatKind.OPEN, MjolnirJson.decodeFromString(ChatKind.serializer(), "\"OPEN\""))
    }

    @Test fun peerPresenceDefaultsAndOldJsonDecodes() {
        val p = MjolnirJson.decodeFromString<Peer>("""{"deviceId":"d","name":"n","kind":"PC"}""")
        assertFalse(p.online); assertEquals(null, p.lastSeenMs)
        val live = Peer("d", "n", DeviceKind.PC, online = true, lastSeenMs = 5)
        assertEquals(live, MjolnirJson.decodeFromString<Peer>(MjolnirJson.encodeToString(Peer.serializer(), live)))
    }

    @Test fun presenceEventUsesItsSerialName() {
        val e = RelayEvent.PresenceChanged("d", false, 7)
        assertEquals("""{"type":"presence","deviceId":"d","online":false,"lastSeenMs":7}""", MjolnirJson.encodeToString(RelayEvent.serializer(), e))
        assertEquals(e, MjolnirJson.decodeFromString(RelayEvent.serializer(), """{"type":"presence","deviceId":"d","online":false,"lastSeenMs":7}"""))
        assertEquals(RelayEvent.PresenceChanged("d", true), MjolnirJson.decodeFromString(RelayEvent.serializer(), """{"type":"presence","deviceId":"d","online":true}"""))
    }
}
