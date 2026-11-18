package app.mjolnir.client

import app.mjolnir.protocol.Tab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.ListSerializer

class JsonFileStoreTest {
    private val dir = createTempDirectory("store").toFile()
    private val ser = ListSerializer(Tab.serializer())
    private fun store(f: File = File(dir, "s.json")) = JsonFileStore(f, ser) { emptyList() }

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
}
