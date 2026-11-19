package app.mjolnir.client

import app.mjolnir.protocol.Tab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer

class JsonFileStoreTest {
    private val dir = createTempDirectory("store").toFile()
    private val ser = ListSerializer(Tab.serializer())
    private fun store(f: File = File(dir, "s.json")) = JsonFileStore(f, ser) { emptyList() }

    @Test fun lockedTargetIsRetriedThenWritten() {
        val s = store(); var calls = 0
        val real = s.move
        s.moveRetryDelayMs = 1
        s.move = { from, to -> if (++calls < 3) throw java.nio.file.AccessDeniedException(to.toString()) else real(from, to) }
        s.write(listOf(Tab("https://a.com")))
        assertEquals(3, calls)
        assertEquals(listOf(Tab("https://a.com")), store().read())
    }

    @Test fun targetLockedForGoodFailsAfterFiveTries() {
        val s = store(); var calls = 0
        s.moveRetryDelayMs = 1
        s.move = { _, to -> calls++; throw java.nio.file.AccessDeniedException(to.toString()) }
        assertFailsWith<java.nio.file.AccessDeniedException> { s.write(listOf(Tab("https://a.com"))) }
        assertEquals(5, calls)
    }

    @Test fun missingFileGivesDefault() { assertEquals(emptyList(), store().read()) }

    @Test fun writeThenReadFromNewInstance() {
        val tabs = listOf(Tab("https://a.com", "A"), Tab("https://b.com"))
        store().write(tabs)
        assertEquals(tabs, store().read())
    }

    @Test fun corruptFileGivesDefault() {
        val f = File(dir, "s.json"); f.writeText("{nope")
        assertEquals(emptyList(), store(f).read())
    }

    // R12: a real read error must propagate, or update() would overwrite the queue with the default.
    @Test fun readErrorPropagatesInsteadOfDefault() {
        val f = File(dir, "s.json").apply { mkdirs() }   // a directory where the file should be
        assertFailsWith<java.io.IOException> { store(f).read() }
        assertFailsWith<java.io.IOException> { store(f).update { it + Tab("https://x.com") } }
        assertTrue(f.isDirectory)
    }
}
