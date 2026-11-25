package app.mjolnir.android.voice

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.mjolnir.android.CHANNEL_VOICE
import app.mjolnir.android.MainActivity
import app.mjolnir.android.MjolnirApp
import app.mjolnir.android.notificationId
import app.mjolnir.android.receive.EXTRA_SCREEN
import app.mjolnir.android.receive.SCREEN_CHATS
import app.mjolnir.client.Messages
import app.mjolnir.client.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

const val ACTION_VOICE_START = "app.mjolnir.voice.START"
const val ACTION_VOICE_MUTE = "app.mjolnir.voice.MUTE"
const val ACTION_VOICE_UNMUTE = "app.mjolnir.voice.UNMUTE"
const val ACTION_VOICE_LEAVE = "app.mjolnir.voice.LEAVE"
private const val EXTRA_ROOM = "room"

internal val VOICE_NOTIFICATION_ID = notificationId("voice")

/** The ongoing notification's title: `Mjolnir voice — {room}`. */
internal fun voiceNotificationTitle(room: String): String = Messages.voiceNotificationTitle(room)

/** The notification's buttons, label to intent action: Mute (or Unmute while muted), then Leave. */
internal fun voiceNotificationActions(muted: Boolean): List<Pair<String, String>> = listOf(
    if (muted) Messages.UNMUTE to ACTION_VOICE_UNMUTE else Messages.MUTE to ACTION_VOICE_MUTE,
    Messages.LEAVE_VOICE to ACTION_VOICE_LEAVE,
)

/** Carries out a notification button's [action] on [voice]; false for anything else. */
internal fun applyVoiceAction(action: String?, voice: VoiceController): Boolean {
    when (action) {
        ACTION_VOICE_MUTE -> voice.setMuted(true)
        ACTION_VOICE_UNMUTE -> voice.setMuted(false)
        ACTION_VOICE_LEAVE -> voice.leave()
        else -> return false
    }
    return true
}

/**
 * The service could not run in the foreground (Android 14 refuses a `microphone` service without the permission or
 * outside the foreground): never voice without it, so the session is left and [report] says why.
 */
internal fun foregroundRefused(voice: VoiceController, report: (String) -> Unit) {
    voice.leave()
    report(Messages.VOICE_AUDIO_ERROR)
}

/**
 * Starts the microphone foreground service for a session just joined in [room]. Call it from the app in the foreground,
 * with the microphone permission granted (Android 14 refuses a `microphone` service otherwise), right before joining.
 * False when Android refused: then the room must not be joined.
 */
fun startVoiceService(context: Context, room: String): Boolean = try {
    context.startForegroundService(Intent(context, VoiceService::class.java).setAction(ACTION_VOICE_START).putExtra(EXTRA_ROOM, room))
    true
} catch (_: RuntimeException) {
    false // not allowed now (e.g. ForegroundServiceStartNotAllowedException): the room is not joined
}

/**
 * Keeps a voice session going with the screen off or the app in the background: a `microphone` foreground service whose
 * lifetime is the session's. It shows `Mjolnir voice — {room}` with Mute (or Unmute) and Leave, and stops itself when
 * the session ends (left here or in the app, or ended by the relay). If the system stops it, the session is left.
 */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var stoppingSelf = false
    private var lastStartId = 0

    private val voice: VoiceController get() = (application as MjolnirApp).container.voice

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val action = intent?.action
        if (action == ACTION_VOICE_START) {
            val active = voice.active.value
            val room = active?.roomName ?: intent.getStringExtra(EXTRA_ROOM).orEmpty()
            try {
                val notification = buildVoiceNotification(this, room, active?.muted == true)
                // The microphone service type exists from Android 11; on 10 the plain foreground service keeps the mic.
                if (Build.VERSION.SDK_INT >= 30) startForeground(VOICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else startForeground(VOICE_NOTIFICATION_ID, notification)
            } catch (_: RuntimeException) {
                // No microphone permission, or not allowed from the background: no voice without the service.
                foregroundRefused(voice) { (application as MjolnirApp).container.voiceServiceNotices.tryEmit(it) }
                stopNow()
                return START_NOT_STICKY
            }
            watch()
        } else {
            applyVoiceAction(action, voice)
            if (voice.active.value == null) stopNow() else if (watcher == null) watch()
        }
        return START_NOT_STICKY
    }

    /** Follows the session: the notification shows its room and mute; once it ends the service goes. */
    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            voice.active.map { it?.let { a -> a.roomName to a.muted } }.distinctUntilChanged().collect { shown ->
                if (shown == null) stopNow()
                else getSystemService(NotificationManager::class.java)
                    ?.notify(VOICE_NOTIFICATION_ID, buildVoiceNotification(this@VoiceService, shown.first, shown.second))
            }
        }
    }

    private fun stopNow() {
        watcher?.cancel()
        watcher = null
        stoppingSelf = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!stopSelfResult(lastStartId)) stoppingSelf = false // a newer start (a new join) keeps it going
    }

    override fun onDestroy() {
        scope.cancel()
        if (!stoppingSelf) voice.leave() // stopped by the system: the session ends with it
        super.onDestroy()
    }
}

/** `Mjolnir voice — {room}`, silent and ongoing; tapping it opens the chat list (where the voice panel is). */
internal fun buildVoiceNotification(context: Context, room: String, muted: Boolean): Notification {
    val open = PendingIntent.getActivity(
        context, 3,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SCREEN, SCREEN_CHATS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = Notification.Builder(context, CHANNEL_VOICE)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle(voiceNotificationTitle(room))
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_SERVICE)
    if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    voiceNotificationActions(muted).forEachIndexed { i, (label, action) ->
        val pending = PendingIntent.getService(
            context, 10 + i, Intent(context, VoiceService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, pending).build())
    }
    return builder.build()
}
