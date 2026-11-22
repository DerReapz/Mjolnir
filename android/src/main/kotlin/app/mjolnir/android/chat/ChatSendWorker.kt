package app.mjolnir.android.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.work.ForegroundInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mjolnir.android.MjolnirApp
import app.mjolnir.android.notificationId
import app.mjolnir.android.postNotification
import app.mjolnir.android.shouldReportProgress
import app.mjolnir.android.ui.sendProgress
import app.mjolnir.client.Messages
import app.mjolnir.client.PendingSend
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val WORK_CHAT_SEND = "chat-send"

/** True while a persisted send can still go on; a failed one waits for the user to dismiss it. */
fun sendsWaiting(pending: List<PendingSend>): Boolean = pending.any { it.failure == null }

/**
 * Ruling CM-R6: the sends with files get their own unique work, run once a network is there, so the sends of a process
 * that died go on in the background. KEEP: a run already queued or running carries on.
 */
fun enqueueChatSends(context: Context) {
    val request = OneTimeWorkRequestBuilder<ChatSendWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(WORK_CHAT_SEND, ExistingWorkPolicy.KEEP, request)
}

/** Calls [enqueue] each time sends start waiting in [pending]: at start with sends left over, and after a send is queued. */
fun scheduleChatSendsWhenWaiting(pending: Flow<List<PendingSend>>, scope: CoroutineScope, enqueue: () -> Unit): Job =
    scope.launch { pending.map(::sendsWaiting).distinctUntilChanged().collect { if (it) enqueue() } }

/** The low-importance channel of the notification shown while chat sends upload in the background (ruling CV-P6). */
const val CHANNEL_CHAT_SENDING = "chat_sending"

fun createChatSendingChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CHANNEL_CHAT_SENDING, Messages.SENDING_TO_CHATS, NotificationManager.IMPORTANCE_LOW))
}

/** The sends still going on (not failed), and how far they are together: done and total bytes. */
fun chatSendTotals(pending: List<PendingSend>): Pair<Long, Long> {
    val going = pending.filter { it.failure == null }
    return going.sumOf { it.sentBytes.coerceIn(0, it.totalBytes) } to going.sumOf { it.totalBytes }
}

/** The sending notification's title: `Sending to Trip`, `Sending to 2 chats`, or `Sending to chats` with none going. */
fun chatSendTitle(pending: List<PendingSend>, chatName: (String) -> String?): String {
    val chats = pending.filter { it.failure == null }.map { it.chatId }.distinct()
    return when (chats.size) {
        0 -> Messages.SENDING_TO_CHATS
        1 -> "Sending to ${chatName(chats.single()) ?: "a chat"}"
        else -> "Sending to ${chats.size} chats"
    }
}

private val CHAT_SEND_NOTIFICATION_ID = notificationId("chat-send-progress")

/**
 * Uploads and posts the persisted chat sends; retried (with backoff) while some can't go on yet, e.g. the network dropped.
 * Ruling CV-P6: it runs as a foreground (dataSync) worker with a progress notification, as a throw does, so a big video
 * keeps uploading past WorkManager's 10-minute limit.
 */
class ChatSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val controller get() = (applicationContext as MjolnirApp).container.chatController

    override suspend fun doWork(): Result {
        val controller = controller
        try {
            setForeground(foregroundInfo(controller.pendingSends.value))
        } catch (e: IllegalStateException) {
            // Not allowed from the background right now (Android 12+): send without the foreground service.
        } catch (e: SecurityException) {
        }
        try {
            coroutineScope {
                val progress = launch {
                    var lastPct = -1
                    var lastMs: Long? = null
                    controller.pendingSends.collect { pending ->
                        val (done, total) = chatSendTotals(pending)
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        val now = SystemClock.elapsedRealtime()
                        if (sendsWaiting(pending) && shouldReportProgress(pct, lastPct, now, lastMs)) {
                            lastPct = pct
                            lastMs = now
                            postNotification(applicationContext, CHAT_SEND_NOTIFICATION_ID, notification(pending))
                        }
                    }
                }
                controller.resumeSends().join()
                progress.cancel()
            }
        } finally {
            // Without the foreground service the notification is ours to remove.
            applicationContext.getSystemService(NotificationManager::class.java).cancel(CHAT_SEND_NOTIFICATION_ID)
        }
        return if (sendsWaiting(controller.pendingSends.value)) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(controller.pendingSends.value)

    private fun foregroundInfo(pending: List<PendingSend>) =
        ForegroundInfo(CHAT_SEND_NOTIFICATION_ID, notification(pending), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(pending: List<PendingSend>): Notification {
        val (done, total) = chatSendTotals(pending)
        val chats = controller.chats.value
        return Notification.Builder(applicationContext, CHANNEL_CHAT_SENDING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(chatSendTitle(pending) { id -> chats.firstOrNull { it.id == id }?.name })
            .setContentText(if (total > 0) sendProgress(done, total) else null)
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun openApp(): PendingIntent? =
        applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let {
            PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
}
