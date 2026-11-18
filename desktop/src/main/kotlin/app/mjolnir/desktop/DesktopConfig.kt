package app.mjolnir.desktop

import java.io.File
import java.util.Properties
import kotlinx.serialization.Serializable

@Serializable
data class DesktopConfig(
    val relayUrl: String,
    val deviceId: String? = null,
    val token: String? = null,
    val deviceName: String,
    val lastPhoneId: String? = null,
    val launchAtLogin: Boolean = false,
    val saveDir: String? = null,
)

/** The save folder: the configured one, else `%USERPROFILE%\Downloads\Mjolnir`; created on demand. */
fun DesktopConfig.saveDirFile(): File {
    val dir = saveDir?.let(::File)
        ?: File(File(System.getenv("USERPROFILE") ?: System.getProperty("user.home"), "Downloads"), "Mjolnir")
    dir.mkdirs()
    return dir
}

fun defaultDesktopConfig(): DesktopConfig {
    val props = Properties()
    DesktopConfig::class.java.getResourceAsStream("/mjolnir.properties")?.use { props.load(it) }
    return DesktopConfig(
        relayUrl = props.getProperty("relayUrl", "").trim(),
        deviceName = System.getenv("COMPUTERNAME") ?: "My PC",
    )
}

/** `%APPDATA%\Mjolnir`, falling back to `~/.mjolnir`. Holds config.json, seen.json and .lock. */
fun appDataDir(): File {
    val appData = System.getenv("APPDATA")
    return if (appData != null) File(appData, "Mjolnir") else File(System.getProperty("user.home"), ".mjolnir")
}
