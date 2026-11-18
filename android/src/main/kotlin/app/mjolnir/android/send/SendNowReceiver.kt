package app.mjolnir.android.send

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mjolnir.android.MjolnirApp
import java.io.IOException

/** The `Send now` action of a waiting-for-Wi-Fi notification: throws that item over mobile data this once. */
class SendNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(KEY_OUTBOX_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                sendNow(app, id)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Allows [outboxId] on a metered network once, schedules it now and removes its waiting notification. Blocking. */
fun sendNow(context: Context, outboxId: String) {
    val app = context.applicationContext
    try {
        (app as MjolnirApp).container.outbox.allowMetered(outboxId)
    } catch (e: IOException) {
        return // nothing changed; the notification stays so the user can try again
    }
    scheduleOutgoing(app, outboxId)
    app.getSystemService(NotificationManager::class.java).cancel(throwNotificationId(outboxId))
}
