package app.mjolnir.desktop

import java.io.File

/** `%APPDATA%\Microsoft\Windows\SendTo\Mjolnir.lnk`. */
fun sendToShortcutFile(appData: File = File(System.getenv("APPDATA") ?: "")): File =
    File(File(File(File(appData, "Microsoft"), "Windows"), "SendTo"), "Mjolnir.lnk")

/** PowerShell ends a single-quoted string at ' and at the typographic quotes U+2018..U+201B; each is doubled. */
private fun psQuote(s: String) = buildString {
    for (ch in s) {
        append(ch)
        if (ch == '\'' || ch in '\u2018'..'\u201B') append(ch)
    }
}

/** PowerShell that creates (or overwrites) the shortcut through the WScript.Shell COM object. */
fun createSendToShortcutCommand(lnk: File, exe: String): List<String> {
    val l = psQuote(lnk.path); val e = psQuote(exe)
    return listOf(
        "powershell", "-NoProfile", "-NonInteractive", "-Command",
        "\$s=(New-Object -ComObject WScript.Shell).CreateShortcut('$l');\$s.TargetPath='$e';\$s.IconLocation='$e,0';" +
            "\$s.Description='Send to a device with Mjolnir';\$s.Save()",
    )
}

/**
 * Adds or removes Explorer's *Send to → Mjolnir* entry. Returns true on success; false, doing nothing, outside an
 * installed app ([exePath] null).
 */
fun setSendToShortcut(
    enabled: Boolean,
    exePath: String? = System.getProperty("jpackage.app-path"),
    lnk: File = sendToShortcutFile(),
    exec: (List<String>) -> Int = ::runProcess,
): Boolean {
    if (exePath == null) return false
    return if (enabled) exec(createSendToShortcutCommand(lnk, exePath)) == 0 else !lnk.exists() || lnk.delete()
}
