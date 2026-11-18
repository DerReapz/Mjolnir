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
