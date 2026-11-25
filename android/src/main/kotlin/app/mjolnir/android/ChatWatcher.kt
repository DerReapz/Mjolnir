package app.mjolnir.android

import app.mjolnir.client.ChatController
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.RelayEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Feeds the relay's events to the [ChatController] for as long as [active] says so (the app in the foreground, on any
 * tab), so messages and presence appear live. In the background the phone relies on the FCM doorbell and the inbox
 * worker. A dropped connection is retried; the controller catches up on every reconnect (`Connected`). A voice session
 * has its own socket, so it is not bound by this foreground rule.
 */
class ChatWatcher(
    private val controller: ChatController,
    private val events: () -> Flow<RelayEvent>,
    scope: CoroutineScope,
    active: Flow<Boolean>,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    /** Also gets every event (the voice rooms: room changes, and `Connected` to refetch). */
    private val alsoTo: (RelayEvent) -> Unit = {},
) {
    init {
        scope.launch {
            active.distinctUntilChanged().collectLatest { on -> if (on) listen() }
        }
    }

    private suspend fun listen() {
        while (true) {
            try {
                events().collect { e -> controller.onEvent(e); alsoTo(e) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RelayException) {
                // Retried below; the next connection catches up.
            }
            delay(retryDelayMs)
        }
    }

    private companion object { const val RETRY_DELAY_MS = 5_000L }
}

/**
 * Turns the controller's [ChatController.newMessages] into notifications, app-wide and always on: it starts collecting
 * at once (an emission with no collector is dropped) and only posts, it never syncs or refreshes. [nicknameOf] is the
 * private nickname of a device id, if any.
 */
class ChatNotifier(
    controller: ChatController,
    scope: CoroutineScope,
    private val nicknameOf: (String) -> String?,
    private val post: (message: app.mjolnir.client.IncomingMessage, authorName: String) -> Unit,
) {
    init {
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            controller.newMessages.collect { post(it, nicknameOf(it.authorId) ?: it.authorName) }
        }
    }
}
