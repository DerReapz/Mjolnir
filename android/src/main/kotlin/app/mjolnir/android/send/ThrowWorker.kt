package app.mjolnir.android.send

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.mjolnir.android.MjolnirApp
import app.mjolnir.android.data.returnToTray
import app.mjolnir.client.Messages
import app.mjolnir.client.OutboxResult
import app.mjolnir.client.OutgoingFiles
import java.io.IOException
import java.util.concurrent.TimeUnit

const val KEY_OUTBOX_ID = "outboxId"
private const val KEY_UNMETERED_ONLY = "unmeteredOnly"
private const val KEY_PCT = "pct"
private const val KEY_NAME = "name"
private const val KEY_WAITING_FOR_WIFI = "waitingForWifi"
private const val TAG_THROW = "throw"
private const val TAG_WAIT_FOR_WIFI = "wait-for-wifi"

/** Results and the waiting-for-Wi-Fi notice: default importance. */
const val CHANNEL_SENDING = "sending"
/** The ongoing progress of a throw: low importance (no sound). */
const val CHANNEL_SENDING_PROGRESS = "sending_progress"

fun uniqueThrowWork(outboxId: String) = "throw-$outboxId"

/** The id of the per-throw notification (waiting for Wi-Fi, then the result); the progress one is separate. */
fun throwNotificationId(outboxId: String) = notificationId("throw:$outboxId")
private fun progressNotificationId(outboxId: String) = notificationId("throw-progress:$outboxId")

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

/**
 * Schedules the background throw of outbox item [outboxId] as unique work `throw-$outboxId`: it runs on any
 * connection, or only on an unmetered one when [unmeteredOnly], and retries with exponential backoff from 30 s.
 */
fun scheduleOutgoing(
    context: Context,
    outboxId: String,
    unmeteredOnly: Boolean = false,
    policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
) {
    val request = OneTimeWorkRequestBuilder<ThrowWorker>()
        .setInputData(workDataOf(KEY_OUTBOX_ID to outboxId, KEY_UNMETERED_ONLY to unmeteredOnly))
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build(),
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(TAG_THROW)
        .apply { if (unmeteredOnly) addTag(TAG_WAIT_FOR_WIFI) }
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(uniqueThrowWork(outboxId), policy, request)
}

/** What an *On the way* row shows, read from the unique work's infos. */
data class OutgoingStatus(val running: Boolean, val progressPct: Int?, val currentName: String?, val waitingForWifi: Boolean) {
    val label: String get() = outgoingLabel(running, progressPct, currentName, waitingForWifi)
}

fun outgoingStatus(infos: List<WorkInfo>): OutgoingStatus {
    val active = infos.filter { !it.state.isFinished }
    val running = active.firstOrNull { it.state == WorkInfo.State.RUNNING && !it.progress.getBoolean(KEY_WAITING_FOR_WIFI, false) }
    val waiting = active.any { TAG_WAIT_FOR_WIFI in it.tags || it.progress.getBoolean(KEY_WAITING_FOR_WIFI, false) }
    val progress = running?.progress
    val pct = progress?.takeIf { it.keyValueMap.containsKey(KEY_PCT) }?.getInt(KEY_PCT, 0)
    return OutgoingStatus(running != null, pct, progress?.getString(KEY_NAME), waiting)
}

/** True when no work for the item is left to run (it finished, failed, was cancelled, or never existed). */
fun throwWorkFinished(infos: List<WorkInfo>): Boolean = infos.all { it.state.isFinished }

fun createSendingChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL_SENDING, "Throwing files", NotificationManager.IMPORTANCE_DEFAULT))
    manager.createNotificationChannel(NotificationChannel(CHANNEL_SENDING_PROGRESS, "Throw progress", NotificationManager.IMPORTANCE_LOW))
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

/** Throws one outbox item in the background (input [KEY_OUTBOX_ID]), resuming where an earlier attempt stopped. */
class ThrowWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as MjolnirApp).container

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_OUTBOX_ID) ?: return Result.success()
        val c = container
        val (item, config) = try {
            (c.outbox.get(id) ?: return Result.success()) to c.config.read()
        } catch (e: IOException) {
            return Result.retry()
        }
        // Work scheduled for an unmetered network only runs on one; don't second-guess it (e.g. behind a VPN).
        val metered = !inputData.getBoolean(KEY_UNMETERED_ONLY, false) &&
            applicationContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        val decision = networkDecision(item.totalBytes, metered, config.largeFilePolicy, config.largeFileThresholdMb, item.allowMetered)
        return when (decision) {
            NetDecision.WAIT_FOR_WIFI -> waitForWifi(item)
            NetDecision.GO -> send(item)
        }
    }

    private suspend fun waitForWifi(item: OutgoingFiles): Result {
        setProgress(workDataOf(KEY_WAITING_FOR_WIFI to true))
        // Appended, not replaced: replacing would cancel this very worker. The follow-up runs once on Wi-Fi.
        scheduleOutgoing(applicationContext, item.id, unmeteredOnly = true, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
        postNotification(applicationContext, throwNotificationId(item.id), waitingNotification(item))
        return Result.success()
    }

    private suspend fun send(item: OutgoingFiles): Result {
        // Its Send now would REPLACE (cancel) this running upload.
        applicationContext.getSystemService(NotificationManager::class.java).cancel(throwNotificationId(item.id))
        try {
            setForeground(foregroundInfo(item, null, null))
        } catch (e: IllegalStateException) {
            // Not allowed from the background right now (Android 12+): send without the foreground service.
        } catch (e: SecurityException) {
        }
        val files = item.files.map { it.relativePath to it.sizeBytes }
        var lastPct = -1
        var lastMs: Long? = null
        val result = try {
            container.outbox.process(item.id) { done, total ->
                val pct = progressPercent(done, total)
                val now = SystemClock.elapsedRealtime()
                if (shouldReportProgress(pct, lastPct, now, lastMs)) {
                    lastPct = pct
                    lastMs = now
                    val name = currentFileName(files, done)
                    setProgressAsync(workDataOf(KEY_PCT to pct, KEY_NAME to name))
                    postNotification(applicationContext, progressNotificationId(item.id), progressNotification(item, pct, name))
                }
            }
        } catch (e: IOException) { // the outbox file couldn't be read or written
            return Result.retry()
        } finally {
            // Without the foreground service the progress notification is ours to remove.
            applicationContext.getSystemService(NotificationManager::class.java).cancel(progressNotificationId(item.id))
        }
        return when (result) {
            is OutboxResult.Done -> {
                notifyResult(item.id, Messages.thrown(0, result.item.files.size, result.item.toName))
                Result.success()
            }
            OutboxResult.Retry -> Result.retry()
            is OutboxResult.Failed -> {
                try {
                    container.tray.update { returnToTray(it, result.item) }
                } catch (e: IOException) {
                    // The outbox entry is gone already; the copies stay on disk but leave the tray.
                }
                notifyResult(item.id, Messages.filesBack(Messages.failureReason(result.kind, result.item.toName)))
                Result.success()
            }
        }
    }

    private fun foregroundInfo(item: OutgoingFiles, pct: Int?, name: String?) =
        ForegroundInfo(progressNotificationId(item.id), progressNotification(item, pct, name), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun progressNotification(item: OutgoingFiles, pct: Int?, name: String?): Notification =
        Notification.Builder(applicationContext, CHANNEL_SENDING_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(rowTitle(item))
            .setContentText(outgoingLabel(true, pct, name, false))
            .setProgress(100, pct ?: 0, pct == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun waitingNotification(item: OutgoingFiles): Notification {
        val sendNow = PendingIntent.getBroadcast(
            applicationContext, 0,
            Intent(applicationContext, SendNowReceiver::class.java)
                .setIdentifier(item.id) // one PendingIntent per item
                .putExtra(KEY_OUTBOX_ID, item.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(applicationContext, CHANNEL_SENDING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentText(Messages.waitingToThrow(item.files.size, item.totalBytes, item.toName))
            .setStyle(Notification.BigTextStyle())
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Send now", sendNow).build())
            .build()
    }

    private fun notifyResult(outboxId: String, text: String) {
        postNotification(
            applicationContext, throwNotificationId(outboxId),
            Notification.Builder(applicationContext, CHANNEL_SENDING)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun openApp(): PendingIntent? =
        applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let {
            PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
}

/** `{items} to {pc}`, the title of an *On the way* row and of the progress notification. */
fun rowTitle(item: OutgoingFiles): String = "${Messages.items(0, item.files.size)} to ${item.toName}"
