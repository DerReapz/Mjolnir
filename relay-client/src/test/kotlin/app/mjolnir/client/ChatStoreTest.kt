package app.mjolnir.client

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.ChatMessage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatStoreTest {
    private val dir = Files.createTempDirectory("chats").toFile().also { it.deleteOnExit() }
    private val sam = Peer("sam", "Sam", DeviceKind.PHONE)
    private fun msg(id: String, rev: Long) = ChatMessage(id, "c1", sam, rev, rev * 10, text = "hi $id")

    @Test fun missingChatReadsEmpty() {
        assertEquals(CachedChat(), ChatStore(dir).read("nope"))
    }

    @Test fun updatePersistsPerChatFileAndSurvivesANewStore() {
        val store = ChatStore(dir)
        store.update("c1") { it.copy(rev = 2, ownerId = "sam", members = listOf(sam), messages = mapOf("m1" to msg("m1", 1), "m2" to msg("m2", 2))) }
        store.update("c2") { it.copy(rev = 7) }
        assertTrue(dir.resolve("chat-c1.json").isFile)
        val again = ChatStore(dir).read("c1")
        assertEquals(2, again.rev); assertEquals("sam", again.ownerId); assertEquals(listOf("m1", "m2"), again.messages.keys.toList())
        assertEquals(7, ChatStore(dir).read("c2").rev)
    }

    @Test fun forgetDeletesTheFileAndOnlyThatChat() {
        val store = ChatStore(dir)
        store.update("c1") { it.copy(rev = 1) }; store.update("c2") { it.copy(rev = 1) }
        store.forget("c1")
        assertFalse(dir.resolve("chat-c1.json").exists())
        assertEquals(CachedChat(), store.read("c1"))
        assertEquals(1, store.read("c2").rev)
        store.update("c1") { it.copy(rev = 5) } // usable again after forgetting
        assertEquals(5, store.read("c1").rev)
    }

    @Test fun oddIdsStayInsideTheDirectory() {
        val store = ChatStore(dir)
        store.update("../evil") { it.copy(rev = 1) }
        assertEquals(1, store.read("../evil").rev)
        assertTrue(dir.listFiles()!!.all { it.name.startsWith("chat-") })
        assertFalse(dir.parentFile.resolve("evil.json").exists())
    }
}
