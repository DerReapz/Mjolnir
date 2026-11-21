package app.mjolnir.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.app.NotificationChannel
import android.content.Intent
import app.mjolnir.android.receive.CHANNEL_CAUGHT
import app.mjolnir.android.receive.EXTRA_CHAT
import app.mjolnir.android.receive.EXTRA_SCREEN
import app.mjolnir.android.receive.SCREEN_CHATS
import app.mjolnir.client.IncomingMessage
import app.mjolnir.client.Messages

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

private const val GUEST_JOINED_ID = 0x6A01

/** `Guest joined`: someone opened this phone's guest room link. Tapping it opens the app. */
internal fun postGuestJoinedNotification(context: Context) {
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
    }
    postNotification(
        context, GUEST_JOINED_ID,
        Notification.Builder(context, CHANNEL_CAUGHT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentText("Guest joined")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build(),
    )
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

/** Messages in chats: default importance. */
const val CHANNEL_CHAT = "chat"

fun createChatChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CHANNEL_CHAT, "Chat messages", NotificationManager.IMPORTANCE_DEFAULT))
}

/** `{name} in {chat}: {text}`: one notification per chat (a newer message replaces the last); tapping it opens that chat. */
internal fun postChatNotification(context: Context, message: IncomingMessage, authorName: String) {
    val text = Messages.chatNotification(authorName, message.chatName, message.text)
    val open = PendingIntent.getActivity(
        context, 2,
        Intent(context, MainActivity::class.java)
            .setIdentifier(message.chatId) // one PendingIntent per chat
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SCREEN, SCREEN_CHATS)
            .putExtra(EXTRA_CHAT, message.chatId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    postNotification(
        context, notificationId("chat:${message.chatId}"),
        Notification.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build(),
    )
}

/** The chat was read in the app: its notification goes. */
internal fun cancelChatNotification(context: Context, chatId: String) {
    context.getSystemService(NotificationManager::class.java).cancel(notificationId("chat:$chatId"))
}
