package app.mjolnir.server

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A "doorbell": wakes the phone so it fetches its inbox. It never carries content. */
interface PushSender { suspend fun ping(pushToken: String) }

object NoopPushSender : PushSender { override suspend fun ping(pushToken: String) {} }

class FcmPushSender(private val messaging: FirebaseMessaging) : PushSender {
    override suspend fun ping(pushToken: String) {
        val message = Message.builder()
            .setToken(pushToken)
            .putData("type", "inbox")
            .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
            .build()
        withContext(Dispatchers.IO) { messaging.send(message) }
    }
}
