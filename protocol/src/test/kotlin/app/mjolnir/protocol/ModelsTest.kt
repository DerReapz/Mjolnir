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
