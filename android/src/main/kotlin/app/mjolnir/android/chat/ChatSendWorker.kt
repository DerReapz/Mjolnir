package app.mjolnir.android.chat

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mjolnir.android.MjolnirApp
import app.mjolnir.client.PendingSend
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

/** Uploads and posts the persisted chat sends; retried (with backoff) while some can't go on yet, e.g. the network dropped. */
class ChatSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val controller = (applicationContext as MjolnirApp).container.chatController
        controller.resumeSends().join()
        return if (sendsWaiting(controller.pendingSends.value)) Result.retry() else Result.success()
    }
}
