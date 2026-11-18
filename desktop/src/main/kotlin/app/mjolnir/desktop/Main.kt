package app.mjolnir.desktop

import androidx.compose.ui.window.application
import app.mjolnir.desktop.ui.TrayApp
import javax.swing.JOptionPane
import kotlin.system.exitProcess

const val ALREADY_RUNNING = "Mjolnir is already running. Look for the hammer in the system tray."

/** Held for the whole process lifetime: dropping it (or letting it be collected) would release the lock. */
@Volatile
private var instanceLock: AutoCloseable? = null

fun main() {
    instanceLock = SingleInstanceLock.tryAcquire(appDataDir())
    if (instanceLock == null) {
        JOptionPane.showMessageDialog(null, ALREADY_RUNNING, "Mjolnir", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }
    val controller = AppController()
    controller.start()
    application(exitProcessOnExit = true) {
        TrayApp(controller, onQuit = {
            controller.shutdown()
            exitApplication()
        })
    }
    instanceLock?.close()
}
