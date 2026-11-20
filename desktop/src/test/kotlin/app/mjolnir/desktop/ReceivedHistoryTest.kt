package app.mjolnir.desktop

import app.mjolnir.client.JsonFileStore
import java.io.File
import kotlin.test.*
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.io.TempDir

class ReceivedHistoryTest {
    private fun store(file: File) = JsonFileStore(file, ListSerializer(ReceivedEntry.serializer())) { emptyList() }
    private fun entry(id: String) = ReceivedEntry(id, "dev", "Pixel", 1L, Kind.LINK, "t$id", "https://a.com/$id")

    @Test fun newestFirst(@TempDir dir: File) {
        val h = ReceivedHistory(store(File(dir, "received.json")))
        h.add(listOf(entry("1"), entry("2")))
        h.add(listOf(entry("3")))
        assertEquals(listOf("3", "1", "2"), h.entries.value.map { it.id })
    }

    @Test fun capsAt100(@TempDir dir: File) {
        val h = ReceivedHistory(store(File(dir, "received.json")))
        h.add((1..150).map { entry("$it") })
        assertEquals(100, h.entries.value.size)
        assertEquals("1", h.entries.value.first().id)
    }

    @Test fun corruptFileReadsAsEmpty(@TempDir dir: File) {
        val f = File(dir, "received.json").apply { writeText("{not json") }
        assertTrue(ReceivedHistory(store(f)).entries.value.isEmpty())
    }

    @Test fun survivesReload(@TempDir dir: File) {
        val f = File(dir, "received.json")
        ReceivedHistory(store(f)).add(listOf(entry("1")))
        assertEquals(listOf("1"), ReceivedHistory(store(f)).entries.value.map { it.id })
    }

    @Test fun clearEmptiesAndPersists(@TempDir dir: File) {
        val f = File(dir, "received.json")
        val h = ReceivedHistory(store(f)); h.add(listOf(entry("1"))); h.clear()
        assertTrue(h.entries.value.isEmpty()); assertTrue(ReceivedHistory(store(f)).entries.value.isEmpty())
    }
}
