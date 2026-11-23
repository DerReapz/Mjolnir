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
    private val commit: (File) -> Unit,
) {
    /** True once the APK went to the installer (its outcome arrives at [InstallStatusReceiver]); false if not allowed. */
    fun install(file: File): Boolean {
        if (!canRequestInstalls()) {
            openInstallSettings()
            return false
        }
        commit(file)
        return true
    }

    companion object {
        fun forApp(context: Context): ApkInstaller {
            val app = context.applicationContext
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
                commit = { file -> commitSession(app, file) },
            )
        }

        /** Writes [file] into a new install session and commits it; the status goes to [InstallStatusReceiver]. */
        private fun commitSession(context: Context, file: File) {
            val installer = context.packageManager.packageInstaller
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
                    // Mutable (API 31+ requires saying so): the installer adds the status extras to this intent.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val status = PendingIntent.getBroadcast(context, id, Intent(context, InstallStatusReceiver::class.java), flags)
                    session.commit(status.intentSender)
                }
            } catch (e: Exception) {
                try { installer.abandonSession(id) } catch (_: Exception) { }
                throw e
            }
        }
    }
}

/**
 * The install session's outcome (not exported; only the system's PendingIntent reaches it). A pending confirmation
 * opens the system's install prompt; any other status goes to the app's [UpdateFlow].
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val flow = (context.applicationContext as MjolnirApp).container.updateFlow
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = confirmIntent(intent)
            try {
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                }
            } catch (e: ActivityNotFoundException) {
                // Falls through to a failure: the banner offers Install again.
            }
            flow.onInstallStatus(PackageInstaller.STATUS_FAILURE, message)
            return
        }
        flow.onInstallStatus(status, message)
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
}
