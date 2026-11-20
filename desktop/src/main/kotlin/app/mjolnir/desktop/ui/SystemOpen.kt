package app.mjolnir.desktop.ui

import java.awt.Desktop
import java.io.File

/** Opens [file] with its default app; false when it is gone or cannot be opened. */
internal fun openFile(file: File): Boolean = try {
    if (!file.exists()) false else { Desktop.getDesktop().open(file); true }
} catch (_: Exception) {
    false
}

/** Shows [file] selected in Explorer (elsewhere: opens its folder); false when it is gone or cannot be shown. */
internal fun revealInFolder(file: File): Boolean = try {
    when {
        !file.exists() -> false
        System.getProperty("os.name").startsWith("Windows") -> { ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start(); true }
        else -> { Desktop.getDesktop().open(file.absoluteFile.parentFile); true }
    }
} catch (_: Exception) {
    false
}

/** The nickname for [deviceId] from a [app.mjolnir.client.Nicknames.changes] snapshot, else [realName] (same rule as `Nicknames.displayName`, but readable from a collected flow). */
internal fun Map<String, String>.nicknameOr(deviceId: String, realName: String): String = this[deviceId] ?: realName
