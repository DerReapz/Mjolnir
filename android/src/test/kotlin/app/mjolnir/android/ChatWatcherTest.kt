package app.mjolnir.android

import app.mjolnir.client.ChatController
import app.mjolnir.client.ChatStore
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.Presence
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatWatcherTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    private val api = FakeRelayApi()
    private val sam = Peer("sam", "Sam", DeviceKind.PHONE)

    @Test fun listensOnlyWhileActiveAndDeliversLiveMessages() = runTest {
        api.asMemberOf("s1", Peer("admin", "Admin", DeviceKind.PC), name = "Crew")
        api.addMember("s1", sam)
        val chat = api.addChat("s1", "General", members = listOf(api.me, sam))
        val controller = ChatController({ api }, ChatStore(temp.newFolder("chats")), CoroutineScope(main.dispatcher)) { api.me.deviceId }
        val active = MutableStateFlow(false)
        ChatWatcher(controller, { api.events() }, CoroutineScope(main.dispatcher), active, retryDelayMs = 10)
        advanceUntilIdle()
        assertEquals(0, api.events.subscriptionCount.value)

        active.value = true; advanceUntilIdle()
        assertEquals(1, api.events.subscriptionCount.value)
        controller.refresh(); advanceUntilIdle()
        api.incoming(chat, sam, "live"); advanceUntilIdle()
        assertEquals(listOf("live"), controller.messages(chat).value.map { it.text })

        active.value = false; advanceUntilIdle()
        assertEquals(0, api.events.subscriptionCount.value)
    }

    @Test fun presenceReachesTheControllerWhileActive() = runTest {
        val controller = ChatController({ api }, ChatStore(temp.newFolder("chats")), CoroutineScope(main.dispatcher)) { api.me.deviceId }
        val foreground = MutableStateFlow(true) // the app is open, on whichever tab
        ChatWatcher(controller, { api.events() }, CoroutineScope(main.dispatcher), foreground, retryDelayMs = 10)
        advanceUntilIdle()
        api.events.emit(RelayEvent.PresenceChanged("sam", online = true)); advanceUntilIdle()
        assertEquals(Presence(true, null), controller.presence.value["sam"])
        foreground.value = false; advanceUntilIdle()
        assertEquals(0, api.events.subscriptionCount.value) // in the background the socket goes
    }
}
