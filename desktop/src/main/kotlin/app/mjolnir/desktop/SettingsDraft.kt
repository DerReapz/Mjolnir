package app.mjolnir.desktop

import app.mjolnir.client.RelayUrls
import app.mjolnir.protocol.Limits

const val USE_HTTPS = "Use an https:// address"
const val RELAY_CHANGE_WARNING = "Changing the relay unpairs all devices."
const val NAME_REQUIRED = "Enter a name for this PC"
val NAME_TOO_LONG = "Use ${Limits.MAX_DEVICE_NAME_LENGTH} characters or fewer"

fun isValidDeviceName(name: String): Boolean = name.trim().length in 1..Limits.MAX_DEVICE_NAME_LENGTH

/** What the Settings tab edits; nothing is applied until Save ([AppController.saveSettings]). */
data class SettingsDraft(
    val relayUrl: String,
    val deviceName: String,
    val saveDir: String,
    val openLinksOnArrival: Boolean,
    val launchAtLogin: Boolean,
    val sendToMenu: Boolean,
    val keepInTray: Boolean,
)

fun DesktopConfig.toDraft() = SettingsDraft(
    relayUrl = relayUrl, deviceName = deviceName, saveDir = saveDirPath().absolutePath,
    openLinksOnArrival = openLinksOnArrival, launchAtLogin = launchAtLogin, sendToMenu = sendToMenu, keepInTray = keepInTray,
)

/** The message for the first thing wrong with the draft (relay address, then name), or null when it can be saved. */
fun SettingsDraft.validate(): String? = when {
    RelayUrls.normalize(relayUrl) == null -> USE_HTTPS
    deviceName.isBlank() -> NAME_REQUIRED
    !isValidDeviceName(deviceName) -> NAME_TOO_LONG
    else -> null
}

/** True when saving would move to another relay, which unpairs every device (so the UI asks first). Nothing to unpair while no relay was set. */
fun SettingsDraft.relayChanged(current: DesktopConfig): Boolean {
    val next = RelayUrls.normalize(relayUrl) ?: return false
    return current.relayUrl.isNotBlank() && next != RelayUrls.normalize(current.relayUrl)
}
