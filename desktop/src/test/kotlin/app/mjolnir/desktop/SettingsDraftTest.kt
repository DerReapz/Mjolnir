package app.mjolnir.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsDraftTest {
    private val draft = SettingsDraft(
        relayUrl = "https://relay.example.com", deviceName = "PC", saveDir = "C:\\save",
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
}
