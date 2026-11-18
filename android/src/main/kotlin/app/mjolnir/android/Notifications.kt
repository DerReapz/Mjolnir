package app.mjolnir.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** A notification id for [key]; never 0, which startForeground refuses. */
internal fun notificationId(key: String): Int = key.hashCode().takeIf { it != 0 } ?: 1

/** Minimum time between two progress updates (setProgress and the notification). */
private const val PROGRESS_INTERVAL_MS = 500L

/**
 * Whether a progress update for [pct] should go out now: only for a new whole percent, at most every
 * [PROGRESS_INTERVAL_MS], except that 100% always goes out.
 */
fun shouldReportProgress(pct: Int, lastPct: Int, nowMs: Long, lastMs: Long?): Boolean = when {
    pct == lastPct -> false
    pct >= 100 || lastMs == null -> true
    else -> nowMs - lastMs >= PROGRESS_INTERVAL_MS
}

/** Posts [notification] when the app may; without the permission nothing is shown. */
internal fun postNotification(context: Context, id: Int, notification: Notification) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    try {
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    } catch (_: SecurityException) {
    }
}
