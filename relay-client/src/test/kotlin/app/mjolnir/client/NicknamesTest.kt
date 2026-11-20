package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

class NicknamesTest {
    private val file = File(createTempDirectory("nick").toFile(), "nicknames.json")
    private fun nicknames() = Nicknames(JsonFileStore(file, MapSerializer(String.serializer(), String.serializer())) { emptyMap() })
    private val peer = Peer("d1", "Pixel 8", DeviceKind.PHONE)

    @Test fun setThenDisplayNameUsesNickname() {
        val n = nicknames()
        n.set("d1", "Dad's phone")
        assertEquals("Dad's phone", n.get("d1"))
        assertEquals("Dad's phone", n.displayName(peer))
        assertEquals("Dad's phone", nicknames().get("d1")) // persisted
    }

    @Test fun blankRemoves() {
        val n = nicknames()
        n.set("d1", "x")
        n.set("d1", "   ")
        assertNull(n.get("d1"))
    }

    @Test fun trimsAndCapsAt40CodePoints() {
        val n = nicknames()
        n.set("d1", "  " + "漢".repeat(45) + "  ")
        assertEquals("漢".repeat(40), n.get("d1"))
        n.set("d2", "😀".repeat(45))
        assertEquals("😀".repeat(40), n.get("d2"))
    }

    @Test fun removeFallsBackToDeviceName() {
        val n = nicknames()
        n.set("d1", "x")
        n.remove("d1")
        assertEquals("Pixel 8", n.displayName(peer))
    }

    @Test fun corruptFileReadsAsEmpty() {
        file.writeText("{oops")
        assertNull(nicknames().get("d1"))
    }

    @Test fun changesEmitsAfterSet() {
        val n = nicknames()
        assertEquals(emptyMap(), n.changes.value)
        n.set("d1", "x")
        assertEquals(mapOf("d1" to "x"), n.changes.value)
        n.remove("d1")
        assertEquals(emptyMap(), n.changes.value)
    }

    @Test fun clearRemovesEveryNickname() {
        val n = nicknames()
        n.set("d1", "a"); n.set("d2", "b")
        n.clear()
        assertNull(n.get("d1"))
        assertEquals(emptyMap(), n.changes.value)
        assertEquals(emptyMap(), nicknames().changes.value) // persisted
    }
}
