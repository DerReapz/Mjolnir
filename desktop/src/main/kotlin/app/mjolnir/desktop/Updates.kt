package app.mjolnir.desktop

import java.io.File

/** Starts the installer for a verified update file; throws when it can't be started. */
fun interface InstallerLauncher {
    fun launch(file: File)
}

/** The reason shown (`Update failed: {reason}. Try again.`) when the installer could not be started. */
const val INSTALLER_DIDNT_START = "the installer didn't start"

/** The per-user MSI upgrade: no admin rights, a progress bar only, and never a reboot. */
fun msiexecCommand(file: File): List<String> = listOf("msiexec", "/i", file.absolutePath, "/passive", "/norestart")

/**
 * Runs [msiexecCommand] for the downloaded MSI; the app exits right after, so the installer can replace its files. On a
 * non-Windows build host there is no msiexec: it only logs.
 */
class MsiInstaller(
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win"),
    private val log: (String) -> Unit = { System.err.println(it) },
) : InstallerLauncher {
    override fun launch(file: File) {
        if (!isWindows) {
            log("Mjolnir: not Windows, so not running ${msiexecCommand(file).joinToString(" ")}")
            return
        }
        ProcessBuilder(msiexecCommand(file)).start()
    }
}
