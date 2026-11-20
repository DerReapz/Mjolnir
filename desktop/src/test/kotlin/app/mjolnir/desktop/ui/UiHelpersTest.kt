package app.mjolnir.desktop.ui

import app.mjolnir.client.LocalUpload
import app.mjolnir.desktop.ItemPreview
import app.mjolnir.desktop.SendState
import app.mjolnir.desktop.displayName
import app.mjolnir.desktop.isValidDeviceName
import app.mjolnir.desktop.openAt
import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class UiHelpersTest {
    private fun up(rel: String, size: Long = 1) = LocalUpload("/x/$rel", rel, "text/plain", size)

    @Test fun countdownFormat() {
        assertEquals("5:00", formatRemaining(300_000)); assertEquals("0:01", formatRemaining(1))
        assertEquals("0:00", formatRemaining(-5)); assertEquals("1:05", formatRemaining(64_200))
    }

    @Test fun relayField() {
        assertEquals("Use an https:// address", relayFieldMessage("http://relay.example.com", "", attempted = false))
        assertNull(relayFieldMessage("", "", attempted = false))
        assertEquals("Use an https:// address", relayFieldMessage("", "", attempted = true))
        assertNull(relayFieldMessage("https://a.example/", "https://a.example", attempted = false))
        assertEquals("Changing the relay unpairs all devices.", relayFieldMessage("https://b.example", "https://a.example", false))
        assertNull(relayFieldMessage("https://b.example", "", false))
        assertNull(relayFieldMessage("https://a.example", "https://a.example/", false)) // same address as stored (raw, unnormalised)
    }

    @Test fun deviceNames() {
        assertTrue(isValidDeviceName(" PC ")); assertFalse(isValidDeviceName("  ")); assertFalse(isValidDeviceName("x".repeat(65)))
    }

    @Test fun groupsAndNames() {
        val files = listOf(up("Photos/a.jpg", 2), up("Photos/2024/b.jpg", 3), up("c.pdf"))
        assertEquals(listOf("Photos" to 5L, "c.pdf" to 1L), fileGroups(files).map { it.name to it.totalBytes })
        assertEquals("c.pdf", displayName(listOf(up("c.pdf"))))
        assertEquals("Photos", displayName(files.take(2)))
        assertEquals("3 files", displayName(files))
    }

    @Test fun openAtSkipsToOffset(@TempDir dir: File) {
        val f = File(dir, "a.txt").apply { writeText("hello") }
        assertEquals("llo", openAt(LocalUpload(f.path, "a.txt", "text/plain", 5), 2).use { it.readBytes().decodeToString() })
    }

    @Test fun previewsOfRemovedItemsAreEvicted() {
        val a = up("a.txt"); val b = up("b.txt")
        val previews = mutableMapOf<String, ItemPreview>(
            a.path to ItemPreview.Missing("a.txt"), b.path to ItemPreview.Missing("b.txt"), "gone" to ItemPreview.Missing("gone"),
        )
        evictStalePreviews(previews, SendState(files = listOf(b)))
        assertEquals(setOf(b.path), previews.keys)
    }
}
