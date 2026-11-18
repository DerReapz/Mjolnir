package app.mjolnir.desktop

private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

fun launchAtLoginCommand(enabled: Boolean, exePath: String): List<String> =
    if (enabled) listOf("reg", "add", RUN_KEY, "/v", "Mjolnir", "/t", "REG_SZ", "/d", "\"$exePath\"", "/f")
    else listOf("reg", "delete", RUN_KEY, "/v", "Mjolnir", "/f")

/** Returns true when the registry command succeeded; false (running nothing) outside an installed app. */
fun setLaunchAtLogin(
    enabled: Boolean,
    exePath: String? = System.getProperty("jpackage.app-path"),
    exec: (List<String>) -> Int = ::runProcess,
): Boolean {
    if (exePath == null) return false
    return exec(launchAtLoginCommand(enabled, exePath)) == 0
}

fun runProcess(command: List<String>): Int = try {
    ProcessBuilder(command).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
} catch (e: Exception) {
    -1
}
