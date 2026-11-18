package app.mjolnir.desktop

import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class SendToShortcutTest {
    // single quotes inside paths are doubled for PowerShell
    @Test fun shortcutCommandQuotesPaths() {
        val cmd = createSendToShortcutCommand(File("C:\\Users\\O'Neil\\SendTo\\Mjolnir.lnk"), "C:\\Users\\O'Neil\\Mjolnir.exe")
        assertTrue(cmd.last().contains("TargetPath='C:\\Users\\O''Neil\\Mjolnir.exe'"))
    }

    // the exact PowerShell argument list
    @Test fun shortcutCommandShape() {
        val lnk = File("C:\\a\\Mjolnir.lnk"); val exe = "C:\\b\\Mjolnir.exe"
        assertEquals(
            listOf(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "\$s=(New-Object -ComObject WScript.Shell).CreateShortcut('${lnk.path}');\$s.TargetPath='$exe';" +
                    "\$s.IconLocation='$exe,0';\$s.Description='Send to a phone with Mjolnir';\$s.Save()",
            ),
            createSendToShortcutCommand(lnk, exe),
        )
    }

    // the shortcut lives under %APPDATA%\Microsoft\Windows\SendTo
    @Test fun shortcutFileLocation() {
        val appData = File("C:\\Users\\a\\AppData\\Roaming")
        assertEquals(File(File(File(File(appData, "Microsoft"), "Windows"), "SendTo"), "Mjolnir.lnk"), sendToShortcutFile(appData))
    }

    // enabling runs the command and reports its success
    @Test fun enablingRunsCommand(@TempDir dir: File) {
        val lnk = File(dir, "Mjolnir.lnk"); val calls = mutableListOf<List<String>>()
        assertTrue(setSendToShortcut(true, "x.exe", lnk, exec = { calls += it; 0 }))
        assertEquals(listOf(createSendToShortcutCommand(lnk, "x.exe")), calls)
        assertFalse(setSendToShortcut(true, "x.exe", lnk, exec = { 1 }))
    }

    // existing lnk + disable → lnk gone, exec not called
    @Test fun disablingDeletesShortcut(@TempDir dir: File) {
        val lnk = File(dir, "Mjolnir.lnk").apply { writeText("lnk") }
        var called = false
        assertTrue(setSendToShortcut(false, "x.exe", lnk, exec = { called = true; 0 }))
        assertFalse(lnk.exists())
        assertFalse(called)
        assertTrue(setSendToShortcut(false, "x.exe", lnk, exec = { called = true; 0 })) // already gone: still fine
    }

    // exePath = null → false, exec never called, nothing deleted
    @Test fun sendToIsNoOpOutsideInstalledApp(@TempDir dir: File) {
        val lnk = File(dir, "Mjolnir.lnk").apply { writeText("lnk") }
        var called = false
        assertFalse(setSendToShortcut(true, exePath = null, lnk = lnk, exec = { called = true; 0 }))
        assertFalse(setSendToShortcut(false, exePath = null, lnk = lnk, exec = { called = true; 0 }))
        assertFalse(called)
        assertTrue(lnk.exists())
    }
}
