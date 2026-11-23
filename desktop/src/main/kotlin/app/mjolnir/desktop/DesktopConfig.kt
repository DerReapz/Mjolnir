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
    /** The last device sent to (PC or phone); the persisted key keeps its old name. */
    val lastPhoneId: String? = null,
    /** The space of the card last sent to; null when that was the device's paired card. */
    val lastSpaceId: String? = null,
    val launchAtLogin: Boolean = false,
    val saveDir: String? = null,
    val sendToMenu: Boolean = true,
    val openLinksOnArrival: Boolean = true,
    val keepInTray: Boolean = true,
)

/** The save folder: the configured one, else `%USERPROFILE%\Downloads\Mjolnir`; created on demand. */
fun DesktopConfig.saveDirFile(): File = saveDirFile(defaultHome())

/** As [saveDirFile], with an explicit home folder; a blank [DesktopConfig.saveDir] means the default. */
internal fun DesktopConfig.saveDirFile(home: String): File = saveDirPath(home).also { it.mkdirs() }

/** The save folder without touching the disk (safe to call from composition). */
fun DesktopConfig.saveDirPath(home: String = defaultHome()): File =
    saveDir?.takeIf { it.isNotBlank() }?.let(::File) ?: File(File(home, "Downloads"), "Mjolnir")

private fun defaultHome(): String = System.getenv("USERPROFILE") ?: System.getProperty("user.home")

/** This build's release version, `1.0.N`, from the generated `app-version.properties` resource. */
fun appVersion(): String {
    val props = Properties()
    DesktopConfig::class.java.getResourceAsStream("/app-version.properties")?.use { props.load(it) }
    return props.getProperty("version", "1.0.0").trim()
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
