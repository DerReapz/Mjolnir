package app.mjolnir.android.receive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.mjolnir.android.AppContainer
import app.mjolnir.android.MainActivity
import app.mjolnir.android.MjolnirApp
import app.mjolnir.android.OpenAllActivity
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.android.notificationId
import app.mjolnir.android.postNotification
import app.mjolnir.android.send.networkDecision
import app.mjolnir.android.send.progressPercent
import app.mjolnir.android.shouldReportProgress
import app.mjolnir.client.InboxDrainer
import app.mjolnir.client.Messages
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.client.cleanStaging
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.FileRef
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Caught tabs and files, and the waiting-for-Wi-Fi notice: default importance. */
const val CHANNEL_CAUGHT = "caught"
/** The ongoing progress of a download: low importance (no sound). */
const val CHANNEL_RECEIVING = "receiving"

const val EXTRA_SCREEN = "screen"
const val SCREEN_CAUGHT = "caught"
const val EXTRA_DELIVERY_ID = "deliveryId"

private const val WORK_DRAIN = "inbox-drain"
private const val WORK_PERIODIC = "inbox-periodic"
private const val WORK_WIFI = "inbox-wifi"
private const val KEY_UNMETERED_ONLY = "unmeteredOnly"

private val RECEIVING_ID = notificationId("inbox-progress")
private val RESET_ID = notificationId("inbox-reset")
fun waitingNotificationId(deliveryId: String) = notificationId("inbox-wait:$deliveryId")
private fun caughtNotificationId(deliveryId: String) = notificationId("caught:$deliveryId")

fun createReceivingChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL_CAUGHT, "Caught tabs and files", NotificationManager.IMPORTANCE_DEFAULT))
    manager.createNotificationChannel(NotificationChannel(CHANNEL_RECEIVING, "Receiving files", NotificationManager.IMPORTANCE_LOW))
}

private fun connected(type: NetworkType) = Constraints.Builder().setRequiredNetworkType(type).build()

/** Drains the inbox soon (expedited when quota allows), appended after any drain already queued. */
fun enqueueInboxDrain(context: Context) {
    val request = OneTimeWorkRequestBuilder<InboxWorker>()
        .setConstraints(connected(NetworkType.CONNECTED))
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(WORK_DRAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
}

/** The safety net when no push arrives: a drain every 15 minutes while connected. */
fun schedulePeriodicInboxDrain(context: Context) {
    val request = PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES)
        .setConstraints(connected(NetworkType.CONNECTED))
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
}

/** A drain that runs once an unmetered network (Wi-Fi) is available, for deliveries waiting for it. */
private fun enqueueWifiInboxDrain(context: Context) {
    val request = OneTimeWorkRequestBuilder<InboxWorker>()
        .setConstraints(connected(NetworkType.UNMETERED))
        .setInputData(workDataOf(KEY_UNMETERED_ONLY to true))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(WORK_WIFI, ExistingWorkPolicy.KEEP, request)
}

/** Receives everything in the inbox: tabs at once, files now or (large ones on mobile data) once on Wi-Fi. */
class InboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container: AppContainer get() = (applicationContext as MjolnirApp).container

    /** Needed for expedited work before Android 12. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(receivingNotification(null, null))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = container
        val config = try {
            c.config.read()
        } catch (e: IOException) {
            return@withContext Result.retry()
        }
        if (config.token == null) return@withContext Result.success() // not registered yet: nothing to drain
        // One drain at a time across all inbox workers, so no delivery is downloaded or published twice.
        c.inboxLock.withLock { drain(c, config) }
    }

    private suspend fun drain(c: AppContainer, config: PhoneConfig): Result {
        // Work scheduled for an unmetered network only runs on one; don't second-guess it (e.g. behind a VPN).
        val metered = !inputData.getBoolean(KEY_UNMETERED_ONLY, false) &&
            applicationContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        var current: Delivery? = null
        var failure: Exception? = null
        val progress = ProgressReporter()
        val catcher = PhoneCatcher(
            api = c.api,
            caught = c.caught,
            stagingDir = c.stagingDir,
            publisher = MediaStoreFilePublisher(applicationContext),
            notify = { text -> current?.let { notifyCaught(it, text) } },
            displayName = c.nicknames::displayName,
            decide = { d ->
                networkDecision(d.files.sumOf { it.sizeBytes }, metered, config.largeFilePolicy, config.largeFileThresholdMb, d.id in c.allowedOnce.read())
            },
            beforeDownload = { d ->
                progress.reset()
                try {
                    setForeground(foregroundInfo(receivingNotification(d.files.first().name, 0)))
                } catch (e: IllegalStateException) {
                    // Not allowed from the background right now (Android 12+): download without the foreground service.
                } catch (e: SecurityException) {
                }
            },
            onProgress = progress::report,
            recordGuest = c.guestRoom::record,
        )
        val drainer = InboxDrainer(c.api, c.seen) { d ->
            current = d
            try {
                catcher.handle(d)
                cancel(waitingNotificationId(d.id)) // it no longer waits
            } catch (e: WaitingForWifi) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
                throw e
            } finally {
                current = null
            }
        }
        try {
            drainer.drain()
        } catch (e: RelayException) {
            failure = e
        } catch (e: IOException) { // seen.json couldn't be read or written
            failure = e
        } finally {
            cancel(RECEIVING_ID)
            c.caughtChanged.value++
        }
        cleanUp(c)
        for (d in catcher.waiting) notifyWaiting(d)
        if (catcher.waiting.isNotEmpty()) enqueueWifiInboxDrain(applicationContext)
        return outcome(c, failure)
    }

    private suspend fun outcome(c: AppContainer, failure: Exception?): Result = when {
        failure == null -> Result.success()
        failure is RelayException && failure.kind == RelayErrorKind.UNAUTHORIZED -> {
            notifyText(RESET_ID, Messages.RESET)
            try {
                c.session.reset()
                syncPushToken(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Registering again failed; the app registers on its next start.
            }
            Result.success()
        }
        failure is IOException -> Result.retry()
        failure is RelayException && failure.kind in RETRYABLE -> Result.retry()
        else -> Result.success() // e.g. an expired file: retrying now won't help; the periodic drain tries again
    }

    /** Drops staged parts and one-off mobile-data permissions of deliveries no longer in the inbox. */
    private suspend fun cleanUp(c: AppContainer) {
        try {
            val inbox = c.api.inbox()
            cleanStaging(c.stagingDir, keepIds = inbox.flatMap { d -> d.files.map { it.id } }.toSet())
            val ids = inbox.map { it.id }.toSet()
            c.allowedOnce.update { it intersect ids }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Next drain cleans up.
        }
    }

    private inner class ProgressReporter {
        private val done = HashMap<String, Long>()
        private var lastPct = -1
        private var lastMs: Long? = null

        fun reset() = synchronized(this) { done.clear(); lastPct = -1; lastMs = null }

        fun report(d: Delivery, ref: FileRef, bytes: Long) = synchronized(this) {
            done[ref.id] = bytes
            val pct = progressPercent(done.values.sum(), d.files.sumOf { it.sizeBytes })
            val now = SystemClock.elapsedRealtime()
            if (shouldReportProgress(pct, lastPct, now, lastMs)) {
                lastPct = pct
                lastMs = now
                postNotification(applicationContext, RECEIVING_ID, receivingNotification(ref.name, pct))
            }
        }
    }

    private fun foregroundInfo(notification: Notification) =
        ForegroundInfo(RECEIVING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun receivingNotification(name: String?, pct: Int?): Notification =
        Notification.Builder(applicationContext, CHANNEL_RECEIVING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText(if (name == null || pct == null) "Receiving…" else "Receiving $name… $pct%")
            .setProgress(100, pct ?: 0, pct == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openCaught(applicationContext))
            .build()

    private fun notifyCaught(d: Delivery, text: String) {
        val id = caughtNotificationId(d.id)
        val builder = Notification.Builder(applicationContext, CHANNEL_CAUGHT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openCaught(applicationContext))
            .setAutoCancel(true)
        val urls = launchableUrls(d)
        if (urls.isNotEmpty()) {
            // An activity, not a BroadcastReceiver trampoline (Android 12+ blocks starting activities from those).
            val openAll = PendingIntent.getActivity(
                applicationContext, 0,
                Intent(applicationContext, OpenAllActivity::class.java)
                    .setIdentifier(d.id) // one PendingIntent per delivery
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putStringArrayListExtra(OpenAllActivity.EXTRA_URLS, ArrayList(urls))
                    .putExtra(OpenAllActivity.EXTRA_NOTIFICATION_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, "Open all links", openAll).build())
        }
        postNotification(applicationContext, id, builder.build())
    }

    private fun notifyWaiting(d: Delivery) {
        val downloadNow = PendingIntent.getBroadcast(
            applicationContext, 0,
            Intent(applicationContext, DownloadNowReceiver::class.java)
                .setIdentifier(d.id) // one PendingIntent per delivery
                .putExtra(EXTRA_DELIVERY_ID, d.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = Messages.waitingToReceive(d.files.size, d.files.sumOf { it.sizeBytes }, container.nicknames.displayName(d.from))
        postNotification(
            applicationContext, waitingNotificationId(d.id),
            Notification.Builder(applicationContext, CHANNEL_CAUGHT)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true) // re-posted by every drain while it waits
                .setContentIntent(openCaught(applicationContext))
                .addAction(Notification.Action.Builder(null, "Download now", downloadNow).build())
                .build(),
        )
    }

    private fun notifyText(id: Int, text: String) = postNotification(
        applicationContext, id,
        Notification.Builder(applicationContext, CHANNEL_CAUGHT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openCaught(applicationContext))
            .setAutoCancel(true)
            .build(),
    )

    private fun cancel(id: Int) = applicationContext.getSystemService(NotificationManager::class.java).cancel(id)

    private companion object {
        val RETRYABLE = setOf(RelayErrorKind.NETWORK, RelayErrorKind.SERVER, RelayErrorKind.RATE_LIMITED)
    }
}

/** Opens the app on the Caught screen. */
fun openCaught(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 1,
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_SCREEN, SCREEN_CAUGHT),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
