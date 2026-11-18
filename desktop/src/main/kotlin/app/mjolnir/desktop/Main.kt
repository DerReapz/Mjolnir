package app.mjolnir.desktop

import androidx.compose.ui.window.application
import app.mjolnir.desktop.ui.TrayApp
import java.io.File
import javax.swing.JOptionPane
import kotlin.system.exitProcess

const val ALREADY_RUNNING = "Mjolnir is already running. Look for the hammer in the system tray."

/** Held for the whole process lifetime: dropping it (or letting it be collected) would release the lock and channel. */
@Volatile
private var instanceLock: AutoCloseable? = null

fun main(args: Array<String>) {
    // Absolute here, so a running instance with another working directory resolves them the same way.
    val paths = args.filter { it.isNotBlank() }.map { File(it).absolutePath }
    val primary = when (val startup = InstanceChannel.start(appDataDir(), paths)) {
        is Startup.Primary -> startup
        Startup.Forwarded -> exitProcess(0)
        Startup.Failed -> {
            JOptionPane.showMessageDialog(null, ALREADY_RUNNING, "Mjolnir", JOptionPane.INFORMATION_MESSAGE)
            exitProcess(0)
        }
    }
    instanceLock = primary.lock
    val controller = AppController()
    controller.start()
    controller.ensureSendToShortcut()
    application(exitProcessOnExit = true) {
        TrayApp(controller, primary.incoming, onQuit = {
            controller.shutdown()
            exitApplication()
        })
    }
    instanceLock?.close()
}
