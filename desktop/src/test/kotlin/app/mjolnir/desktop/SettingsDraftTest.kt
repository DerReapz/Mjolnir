package app.mjolnir.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsDraftTest {
    private val draft = SettingsDraft(
        relayUrl = "https://relay.example.com", deviceName = "PC", saveDir = "",
        openLinksOnArrival = true, launchAtLogin = false, sendToMenu = true, keepInTray = true,
    )

    @Test fun saveRejectsInvalidRelay() {
        assertNull(draft.validate())
        assertEquals(USE_HTTPS, draft.copy(relayUrl = "http://relay.example.com").validate())
        assertEquals(USE_HTTPS, draft.copy(relayUrl = "").validate())
        assertEquals(USE_HTTPS, draft.copy(relayUrl = "not a url").validate())
    }

    @Test fun emptyNameRejected() {
        assertEquals(NAME_REQUIRED, draft.copy(deviceName = "   ").validate())
        assertEquals(NAME_REQUIRED, draft.copy(deviceName = "").validate())
        assertEquals(NAME_TOO_LONG, draft.copy(deviceName = "x".repeat(65)).validate())
    }

    @Test fun relayChangedDetectsDifferentHost() {
        val current = DesktopConfig(relayUrl = "https://a.example", deviceName = "PC")
        assertFalse(draft.copy(relayUrl = "https://a.example").relayChanged(current))
        assertFalse(draft.copy(relayUrl = " https://a.example/ ").relayChanged(current)) // same address once normalised
        assertTrue(draft.copy(relayUrl = "https://b.example").relayChanged(current))
        assertFalse(draft.copy(relayUrl = "https://b.example").relayChanged(current.copy(relayUrl = ""))) // nothing to unpair yet
    }

    @Test fun draftStartsFromConfig() {
        val config = DesktopConfig(relayUrl = "https://a.example", deviceName = "Desk", openLinksOnArrival = false, keepInTray = false, launchAtLogin = true, sendToMenu = false)
        val d = config.toDraft()
        assertEquals(SettingsDraft("https://a.example", "Desk", config.saveDirPath().absolutePath, false, true, false, false), d)
    }

    @Test fun saveRejectsUnwritableFolder(@org.junit.jupiter.api.io.TempDir dir: java.io.File) {
        val file = java.io.File(dir, "plain.txt").apply { writeText("x") }
        assertEquals(SAVE_FOLDER_UNUSABLE, draft.copy(saveDir = file.path).validate()) // a file, not a folder
        assertEquals(SAVE_FOLDER_UNUSABLE, draft.copy(saveDir = java.io.File(file, "sub").path).validate()) // cannot be created under a file
        assertNull(draft.copy(saveDir = java.io.File(dir, "new/inner").path).validate()) // created on demand
        assertNull(draft.copy(saveDir = "  ").validate()) // blank means the default
    }
}
