package app.mjolnir.android.receive

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mjolnir.android.MjolnirApp
import java.io.IOException

/** The `Download now` action of a waiting-for-Wi-Fi notification: receives that delivery over mobile data this once. */
class DownloadNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_DELIVERY_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                try {
                    (app as MjolnirApp).container.allowedOnce.update { it + id }
                } catch (e: IOException) {
                    return@Thread // nothing changed; the notification stays so the user can try again
                }
                enqueueInboxDrain(app)
                app.getSystemService(NotificationManager::class.java).cancel(waitingNotificationId(id))
            } finally {
                pending.finish()
            }
        }.start()
    }
}
