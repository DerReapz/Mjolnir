package app.mjolnir.android.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import app.mjolnir.android.MjolnirApp
import java.io.File

/**
 * Hands a verified APK to the system installer. Without the user's permission to install apps
 * ([canRequestInstalls]), it opens that setting instead and returns false; the banner then asks the user to allow it.
 */
class ApkInstaller(
    private val canRequestInstalls: () -> Boolean,
    private val openInstallSettings: () -> Unit,
    /** Creates a session holding the APK and returns its id (not committed yet). */
    private val prepare: (File) -> Int,
    private val commit: (sessionId: Int) -> Unit,
    /** Drops a session; never throws. */
    val abandon: (sessionId: Int) -> Unit,
    /** True while the system still has the session (not finished, failed or abandoned). */
    val isLive: (sessionId: Int) -> Boolean,
    /** Opens the system's confirmation prompt for a session; throws when it can't be opened. */
    val launchConfirm: (Intent) -> Unit,
) {
    /**
     * True once the APK went to the installer: [onSession] gets the session's id before the commit, so its status (at
     * [InstallStatusReceiver]) can always be matched. False if installing isn't allowed yet.
     */
    fun install(file: File, onSession: (Int) -> Unit): Boolean {
        if (!canRequestInstalls()) {
            openInstallSettings()
            return false
        }
        val id = prepare(file)
        try {
            onSession(id)
            commit(id)
        } catch (e: Exception) {
            abandon(id)
            throw e
        }
        return true
    }

    companion object {
        fun forApp(context: Context): ApkInstaller {
            val app = context.applicationContext
            val installer = app.packageManager.packageInstaller
            return ApkInstaller(
                canRequestInstalls = { app.packageManager.canRequestPackageInstalls() },
                openInstallSettings = {
                    try {
                        app.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: ActivityNotFoundException) {
                        // No such screen on this device; the banner still says what to allow.
                    }
                },
                prepare = { file -> prepareSession(app, file) },
                commit = { id -> commitSession(app, id) },
                abandon = { id ->
                    try { installer.abandonSession(id) } catch (_: Exception) { } // already finished or gone
                },
                isLive = { id -> installer.getSessionInfo(id) != null },
                launchConfirm = { confirm -> app.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            )
        }

        /**
         * A new install session with [file] written into it; abandoned again if the write fails. Any earlier session of
         * this app (one still waiting, e.g. from before the process restarted) is abandoned first.
         */
        private fun prepareSession(context: Context, file: File): Int {
            val installer = context.packageManager.packageInstaller
            for (old in installer.mySessions) {
                try { installer.abandonSession(old.sessionId) } catch (_: Exception) { }
            }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
            }
            val id = installer.createSession(params)
            try {
                installer.openSession(id).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("mjolnir.apk", 0, file.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                }
            } catch (e: Exception) {
                try { installer.abandonSession(id) } catch (_: Exception) { }
                throw e
            }
            return id
        }

        /** Commits session [id]; its status goes to [InstallStatusReceiver]. */
        private fun commitSession(context: Context, id: Int) {
            context.packageManager.packageInstaller.openSession(id).use { session ->
                // Mutable (API 31+ requires saying so): the installer adds the status extras to this intent.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val status = PendingIntent.getBroadcast(context, id, Intent(context, InstallStatusReceiver::class.java), flags)
                session.commit(status.intentSender)
            }
        }
    }
}

/**
 * The install session's outcome (not exported; only the system's PendingIntent reaches it), handed to the app's
 * [UpdateFlow], which decides whether the system's prompt can be opened now.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A status naming another package is not about our session.
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        if (pkg != null && pkg != context.packageName) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val flow = (context.applicationContext as MjolnirApp).container.updateFlow
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) flow.onConfirmationNeeded(sessionId, confirmIntent(intent))
        else flow.onInstallStatus(sessionId, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
}
