package app.mjolnir.client

import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class ChatMediaCacheTest {
    private val dir = createTempDirectory("media-cache").toFile()
    private val api = FakeRelayApi()

    private fun att(id: String, size: Int, thumb: String? = null): Attachment {
        api.blobs[id] = ByteArray(size) { (it + id.hashCode()).toByte() }
        return Attachment(id, AttachmentKind.FILE, "$id.bin", size.toLong(), "application/octet-stream", thumbFileId = thumb)
    }

    @Test fun getDownloadsOnce() = runTest {
        val a = att("f1", 10)
        val cache = ChatMediaCache(dir)
        val files = (1..3).map { async { cache.get(a, api) } }.awaitAll() + cache.get(a, api)
        assertEquals(listOf("f1"), api.chatDownloads)
        assertTrue(files.all { it == files.first() })
        assertEquals(api.blobs.getValue("f1").toList(), files.first().readBytes().toList())
        // Still cached after a restart.
        assertEquals(files.first(), ChatMediaCache(dir).get(a, api))
        assertEquals(listOf("f1"), api.chatDownloads)
        // A thumbnail is cached the same way, under its own id.
        api.blobs["t1"] = byteArrayOf(1, 2, 3)
        val withThumb = a.copy(thumbFileId = "t1")
        assertEquals(listOf<Byte>(1, 2, 3), cache.thumbnail(withThumb, api)!!.readBytes().toList())
        cache.thumbnail(withThumb, api)
        assertEquals(listOf("f1", "t1"), api.chatDownloads)
        assertEquals(null, cache.thumbnail(a, api))
    }

    @Test fun cacheEvictsLruNotPinned() = runTest {
        val cache = ChatMediaCache(dir, capBytes = 30)
        val a = att("a", 10); val b = att("b", 10); val c = att("c", 10); val d = att("d", 10); val e = att("e", 10)
        cache.pin("a") // on screen
        cache.get(a, api); cache.get(b, api); cache.get(c, api)
        cache.get(b, api) // b is now more recent than c
        cache.get(d, api) // 40 bytes: the least recently used unpinned entry (c) goes, never the pinned a
        assertEquals(setOf("a", "b", "d"), cachedIds())
        cache.unpin("a")
        cache.get(e, api) // a is the least recently used and no longer pinned
        assertEquals(setOf("b", "d", "e"), cachedIds())
        // Pins count: two bubbles showing the same file keep it until both are gone.
        cache.pin("b"); cache.pin("b"); cache.unpin("b")
        cache.get(a, api); cache.get(c, api)
        assertTrue("b" in cachedIds())
        assertEquals(30L, dir.listFiles()!!.filter { it.isFile }.sumOf { it.length() })
    }

    @Test fun failedDownloadLeavesNothing() = runTest {
        val cache = ChatMediaCache(dir)
        val gone = Attachment("nope", AttachmentKind.FILE, "x", 5, "application/octet-stream")
        val error = assertFailsWith<RelayException> { cache.get(gone, api) }
        assertEquals(RelayErrorKind.NOT_FOUND, error.kind)
        assertTrue(dir.listFiles()!!.isEmpty(), dir.listFiles()!!.map { it.name }.toString())
        assertFalse(File(dir, "nope").exists())
    }

    @Test fun getReportsProgressAgainstTheAttachmentSize() = runTest {
        val cache = ChatMediaCache(dir)
        val a = att("v1", 40)
        val seen = mutableListOf<Pair<Long, Long>>()
        cache.get(a, api) { done, total -> seen += done to total }
        assertEquals(40L to 40L, seen.last())
        assertTrue(seen.all { it.second == 40L })
    }

    @Test fun downloadOfTheWrongLengthIsDropped() = runTest {
        val cache = ChatMediaCache(dir)
        val a = att("v1", 40).copy(sizeBytes = 50) // the relay sent less than the message says
        val error = assertFailsWith<RelayException> { cache.get(a, api) }
        assertEquals(RelayErrorKind.NETWORK, error.kind)
        assertEquals("incomplete_download", error.message)
        assertTrue(cachedIds().isEmpty(), cachedIds().toString())
        // A good copy later is kept.
        cache.get(a.copy(sizeBytes = 40), api)
        assertEquals(setOf("v1"), cachedIds())
    }

    @Test fun pinnedEntryOverTheCapStaysUntilUnpinned() = runTest {
        val cache = ChatMediaCache(dir, capBytes = 30)
        val small = att("s", 10); val other = att("o", 10); val video = att("v", 50)
        cache.get(small, api); cache.get(other, api)
        cache.pin("v") // playing
        cache.get(video, api) // alone over the cap: every unpinned entry goes, the video stays
        assertEquals(setOf("v"), cachedIds())
        cache.get(small, api) // the one just fetched is kept beside the pinned video
        assertEquals(setOf("v", "s"), cachedIds())
        cache.unpin("v") // stopped: over the cap, so the video goes
        assertEquals(setOf("s"), cachedIds())
    }

    private fun cachedIds() = dir.listFiles()!!.filter { it.isFile }.map { it.name }.toSet()
}
