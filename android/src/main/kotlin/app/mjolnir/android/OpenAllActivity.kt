package app.mjolnir.android

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import app.mjolnir.android.receive.openTabs
import app.mjolnir.protocol.Tab

/**
 * Invisible activity behind a Caught notification's `Open all links` action: opens the links (re-checked) and finishes.
 * Notification actions must start activities directly; a BroadcastReceiver trampoline is blocked on Android 12+.
 */
class OpenAllActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
            openTabs(this, urls.map { Tab(it) })
            val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
            if (id != 0) getSystemService(NotificationManager::class.java).cancel(id)
        }
        finish()
    }

    companion object {
        const val EXTRA_URLS = "urls"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }
}
