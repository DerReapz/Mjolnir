package app.mjolnir.desktop

import kotlin.test.*

class LaunchAtLoginTest {
    // the exact reg.exe argument lists
    @Test fun launchAtLoginCommands() {
        val exe = "C:\\Users\\a\\AppData\\Local\\Mjolnir\\Mjolnir.exe"; val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        assertEquals(listOf("reg", "add", key, "/v", "Mjolnir", "/t", "REG_SZ", "/d", "\"$exe\" --background", "/f"), launchAtLoginCommand(true, exe))
        assertEquals(listOf("reg", "delete", key, "/v", "Mjolnir", "/f"), launchAtLoginCommand(false, exe))
    }
    // signing in starts in the tray; the Start menu shortcut (no flag) opens the window
    @Test fun launchAtLoginCommandStartsInBackground() {
        assertTrue(launchAtLoginCommand(true, "C:\\x.exe").any { it.endsWith(BACKGROUND_FLAG) })
    }
    // not installed: no exec, false
    @Test fun launchAtLoginIsNoOpOutsideInstalledApp() {
        var called = false
        assertFalse(setLaunchAtLogin(true, exePath = null, exec = { called = true; 0 }))
        assertFalse(called)
    }
}
