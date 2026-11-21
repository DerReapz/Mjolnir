package app.mjolnir.client

import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.Peer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/** What this device knows of one chat: everything up to [rev], with messages (system entries included) by id. */
@Serializable
data class CachedChat(
    val rev: Long = 0,
    val ownerId: String = "",
    val members: List<Peer> = emptyList(),
    val messages: Map<String, ChatMessage> = emptyMap(),
)

/** The on-device chat cache: one JSON file per chat, `chat-{id}.json` in [dir]. */
class ChatStore(private val dir: File) {
    private val stores = ConcurrentHashMap<String, JsonFileStore<CachedChat>>()

    private fun fileFor(chatId: String) = File(dir, "chat-${chatId.replace(UNSAFE, "_")}.json")

    private fun store(chatId: String) = stores.getOrPut(chatId) { JsonFileStore(fileFor(chatId), CachedChat.serializer()) { CachedChat() } }

    /** The cached chat, or an empty one (rev 0) when nothing is cached. */
    fun read(chatId: String): CachedChat = store(chatId).read()

    fun update(chatId: String, transform: (CachedChat) -> CachedChat): CachedChat = store(chatId).update(transform)

    /** The ids of the chats that have a cache file. */
    fun cachedIds(): Set<String> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("chat-") && f.name.endsWith(".json") }
            .orEmpty().mapTo(linkedSetOf()) { it.name.removePrefix("chat-").removeSuffix(".json") }

    /** Drops the cache of [chatId], file included. */
    fun forget(chatId: String) {
        stores.remove(chatId)
        val file = fileFor(chatId)
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
