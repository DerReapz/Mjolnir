package app.mjolnir.android.receive

import android.content.Context
import app.mjolnir.android.MjolnirApp
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The relay's "doorbell": a data message `{"type":"inbox"}` (no content) that triggers a drain. */
class MjolnirMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "inbox") enqueueInboxDrain(applicationContext)
    }

    override fun onNewToken(token: String) {
        val app = applicationContext as MjolnirApp
        app.appScope.launch { sendPushToken(app, token) }
    }
}

/** Fetches the FCM token and gives it to the relay. Does nothing when Firebase isn't set up (no google-services.json). */
fun syncPushToken(context: Context) {
    val app = context.applicationContext as MjolnirApp
    if (FirebaseApp.getApps(app).isEmpty()) return
    try {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            app.appScope.launch { sendPushToken(app, token) }
        }
    } catch (e: IllegalStateException) {
        // Firebase not usable: the phone still gets items on start and on the periodic drain.
    }
}

private suspend fun sendPushToken(app: MjolnirApp, token: String) {
    try {
        if (app.container.config.read().token == null) return // registered later; that path syncs again
        app.container.api.setPushToken(token)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // RelayException, IOException, or no relay URL yet; the next start tries again.
    }
}
