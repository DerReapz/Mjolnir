package app.mjolnir.desktop

import androidx.compose.ui.window.application
import app.mjolnir.desktop.ui.TrayApp
import javax.swing.JOptionPane
import kotlin.system.exitProcess

const val ALREADY_RUNNING = "Mjolnir is already running. Look for the hammer in the system tray."

/** Held for the whole process lifetime: dropping it (or letting it be collected) would release the lock and channel. */
@Volatile
private var instanceLock: AutoCloseable? = null

fun main(args: Array<String>) {
    // Absolute here, so a running instance with another working directory resolves them the same way.
    val launch = parseLaunchArgs(args)
    val primary = when (val startup = InstanceChannel.start(appDataDir(), launch.paths)) {
        is Startup.Primary -> startup
        Startup.Forwarded -> exitProcess(0)
        Startup.Failed -> {
            JOptionPane.showMessageDialog(null, ALREADY_RUNNING, "Mjolnir", JOptionPane.INFORMATION_MESSAGE)
            exitProcess(0)
        }
    }
    instanceLock = primary.lock
    // application(exitProcessOnExit = true) ends the process itself: release the lock and delete ipc.json on the way out.
    Runtime.getRuntime().addShutdownHook(Thread { instanceLock?.close() })
    val controller = AppController()
    controller.start()
    controller.ensureSendToShortcut()
    application(exitProcessOnExit = true) {
        TrayApp(controller, primary.incoming, background = launch.background, onQuit = {
            controller.shutdown()
            instanceLock?.close() // idempotent; the shutdown hook covers other exits
            exitApplication()
        })
    }
    instanceLock?.close()
}
