package app.mjolnir.client

import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Receipt
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/**
 * What this device knows of one chat: everything up to [rev], with messages (system entries included) by id.
 * [readRev] and [name] are the last values the relay reported, so unread counts and notifications work before the
 * first refresh. [announcedRev] is the watermark of messages already announced (or deliberately skipped as history):
 * cached messages above it were fetched but not yet announced, e.g. when a later page of a sync failed.
 */
@Serializable
data class CachedChat(
    val rev: Long = 0,
    val ownerId: String = "",
    val members: List<Peer> = emptyList(),
    val messages: Map<String, ChatMessage> = emptyMap(),
    val readRev: Long = 0,
    val name: String = "",
    val announcedRev: Long = 0,
    /** Who has seen and read what, as of the last sync. */
    val receipts: List<Receipt> = emptyList(),
    /** The rev this device last reported as seen to the relay. */
    val seenRev: Long = 0,
    /** The chat was listed with no messages and none synced since: its first messages are news, also after a restart. */
    val listedEmpty: Boolean = false,
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

    /** Deletes every chat's cache file (and any half-written one). */
    fun clearAll() {
        stores.clear()
        dir.listFiles { f -> f.isFile && f.name.startsWith("chat-") }.orEmpty().forEach { it.delete() }
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
