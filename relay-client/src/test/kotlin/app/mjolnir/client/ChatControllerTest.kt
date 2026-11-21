package app.mjolnir.client

import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.InviteState
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class ChatControllerTest {
    private val sam = Peer("sam", "Sam", DeviceKind.PHONE)
    private val admin = Peer("admin", "Admin", DeviceKind.PC)
    private val dir: File = Files.createTempDirectory("chat-cache").toFile().also { it.deleteOnExit() }

    /** A fake relay where "dev-1" (me) and Sam are members of space s1, administered by Admin, with chat [chatId] = "General". */
    private fun fakeWithChat(): Pair<FakeRelayApi, String> {
        val api = FakeRelayApi()
        api.asMemberOf("s1", admin, name = "Crew")
        api.addMember("s1", sam)
        return api to api.addChat("s1", "General", owner = api.me, members = listOf(api.me, sam))
    }

    private fun TestScope.controller(
        api: FakeRelayApi,
        store: ChatStore = ChatStore(dir),
        scope: CoroutineScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
    ) = ChatController({ api }, store, scope) { api.me.deviceId }

    @Test fun syncAppliesNewEditedDeleted() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        val m1 = api.incoming(chat, sam, "one"); val m2 = api.incoming(chat, sam, "two")
        c.sync(chat)
        assertEquals(listOf("one", "two"), c.messages(chat).value.map { it.text })
        api.incomingEdit(chat, m1.id, "uno"); api.incomingDelete(chat, m2.id)
        api.incoming(chat, sam, "three")
        c.sync(chat)
        val shown = c.messages(chat).value
        assertEquals(listOf("uno", null, "three"), shown.map { it.text })
        assertEquals(listOf(true, false), listOf(shown[0].editedAtEpochMs != null, shown[2].editedAtEpochMs != null))
        assertTrue(shown[1].deleted)
        assertEquals(listOf(m1.id, m2.id), shown.take(2).map { it.id }) // an edit never reorders
    }

    @Test fun syncFollowsHasMore() = runTest {
        val (api, chat) = fakeWithChat()
        repeat(450) { api.incoming(chat, sam, "m$it") }
        val c = controller(api)
        c.sync(chat)
        assertEquals(450, c.messages(chat).value.size)
        assertEquals(3, api.syncCalls.size) // 200 + 200 + 50
        assertEquals(listOf(0L, 200L, 400L), api.syncCalls.map { it.second })
        assertEquals(450L, ChatStore(dir).read(chat).rev)
    }

    @Test fun syncNotFoundForgetsChat() = runTest {
        val (api, chat) = fakeWithChat()
        api.incoming(chat, sam, "hi")
        val c = controller(api)
        c.refresh()
        assertEquals(listOf(chat), c.chats.value.map { it.id })
        api.dropChat(chat)
        c.sync(chat)
        assertTrue(c.messages(chat).value.isEmpty())
        assertTrue(c.chats.value.isEmpty())
        assertTrue(c.unread.value.isEmpty())
        assertFalse(File(dir, "chat-$chat.json").exists())
    }

    @Test fun chatChangedTriggersSync() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val m = api.incoming(chat, sam, "ping")
        c.onEvent(RelayEvent.ChatChanged(chat, m.rev))
        assertEquals(listOf("ping"), c.messages(chat).value.map { it.text })
        val calls = api.syncCalls.size
        c.onEvent(RelayEvent.ChatChanged(chat, m.rev)) // already have that rev
        assertEquals(calls, api.syncCalls.size)
    }

    @Test fun chatChangedForAnUnknownChatRefreshesFirst() = runTest {
        val (api, _) = fakeWithChat()
        val c = controller(api)
        val other = api.addChat("s1", "Plans", owner = sam, members = listOf(sam, api.me))
        val m = api.incoming(other, sam, "hello")
        c.onEvent(RelayEvent.ChatChanged(other, m.rev))
        assertEquals(setOf("General", "Plans"), c.chats.value.map { it.name }.toSet())
        assertEquals(listOf("hello"), c.messages(other).value.map { it.text })
    }

    @Test fun chatDeletedForgetsChatAndItsInvites() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh(); api.incoming(chat, sam, "x"); c.sync(chat)
        api.addMember("s1", Peer("zed", "Zed", DeviceKind.PHONE))
        api.invitedBy(sam, chat, "zed") // pending my approval as the owner
        c.refreshInvites()
        assertEquals(1, c.invites.value.size)
        c.onEvent(RelayEvent.ChatDeleted(chat))
        assertTrue(c.messages(chat).value.isEmpty()); assertTrue(c.chats.value.isEmpty())
        assertTrue(c.invites.value.none { it.chatId == chat })
        assertFalse(File(dir, "chat-$chat.json").exists())
    }

    @Test fun unreadCountsFromReadRev() = runTest {
        val (api, chat) = fakeWithChat()
        api.incoming(chat, sam, "a")
        val mine = api.postMessage(chat, "mine") // rev 2
        api.incoming(chat, sam, "b")            // rev 3
        api.renameChat(chat, "Renamed")         // rev 4: a system entry, never unread
        val gone = api.incoming(chat, sam, "c") // rev 5
        api.incomingDelete(chat, gone.id)       // rev 6: deleted, never unread
        api.markChatRead(chat, mine.rev)
        val c = controller(api)
        c.refresh()
        // Only "b" is by someone else, undeleted, and above readRev 2; `rev - readRev` would say 4.
        assertEquals(mapOf(chat to 1), c.unread.value)
        c.markRead(chat)
        assertTrue(c.unread.value.isEmpty())
        assertEquals(6L, api.readRevOf(chat))
        api.incoming(chat, sam, "d")
        c.sync(chat)
        assertEquals(mapOf(chat to 1), c.unread.value)
    }

    @Test fun openChatSuppressesIncoming() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val seen = mutableListOf<IncomingMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.newMessages.collect { seen += it } }
        api.incoming(chat, sam, "while closed")
        c.sync(chat)
        assertEquals(listOf(IncomingMessage(chat, "General", "Sam", "while closed")), seen)
        c.openChat.value = chat
        api.incoming(chat, sam, "while open")
        c.sync(chat)
        assertEquals(1, seen.size)
        assertEquals(2, c.messages(chat).value.size)
    }

    @Test fun ownMessagesNeverIncoming() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val seen = mutableListOf<IncomingMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.newMessages.collect { seen += it } }
        val sent = c.send(chat, "from me")
        c.sync(chat)
        api.incomingEdit(chat, sent.id, "from me, edited")
        api.renameChat(chat, "Renamed")   // a system entry by me
        api.incoming(chat, sam, "old"); c.sync(chat); seen.clear()
        api.incomingEdit(chat, c.messages(chat).value.last().id, "old, edited"); c.sync(chat) // an edit is not a new message
        api.incomingDelete(chat, c.messages(chat).value.last().id); c.sync(chat)
        assertTrue(seen.isEmpty(), "got $seen")
        assertEquals(listOf("from me, edited"), c.messages(chat).value.filter { it.author.deviceId == "dev-1" && it.event == null }.map { it.text })
    }

    @Test fun firstCatchUpOfAChatStaysSilent() = runTest {
        val (api, chat) = fakeWithChat()
        repeat(3) { api.incoming(chat, sam, "history $it") }
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.newMessages.collect { seen += it } }
        c.refresh()
        assertEquals(3, c.messages(chat).value.size)
        assertEquals(mapOf(chat to 3), c.unread.value)
        assertTrue(seen.isEmpty())
    }

    @Test fun inviteActionsRefreshInvites() = runTest {
        val (api, chat) = fakeWithChat()
        val other = Peer("zed", "Zed", DeviceKind.PHONE)
        api.addMember("s1", other)
        val c = controller(api)
        // Sam (not the owner) invites Zed: the owner (me) has to approve.
        val pending = api.invitedBy(sam, chat, other.deviceId)
        c.refreshInvites()
        assertEquals(listOf(InviteState.PENDING_APPROVAL), c.invites.value.map { it.state })
        c.approveInvite(pending.id)
        assertTrue(c.invites.value.isEmpty()) // approved invites await the invitee, not me
        val second = api.invitedBy(sam, chat, admin.deviceId)
        c.refreshInvites()
        c.denyInvite(second.id)
        assertTrue(c.invites.value.isEmpty())
        // Me being invited to another chat: Join / Decline.
        val plans = api.addChat("s1", "Plans", owner = sam, members = listOf(sam))
        val mine = api.invitedBy(sam, plans, api.me.deviceId)
        c.refreshInvites()
        assertEquals(listOf(mine.id), c.invites.value.map { it.id })
        c.joinInvite(mine.id)
        assertTrue(c.invites.value.isEmpty())
        assertTrue(c.chats.value.any { it.id == plans && it.member })
        val third = api.addChat("s1", "Games", owner = sam, members = listOf(sam))
        val declined = api.invitedBy(sam, third, api.me.deviceId)
        c.onEvent(RelayEvent.ChatInvitesChanged)
        assertEquals(listOf(declined.id), c.invites.value.map { it.id })
        c.declineInvite(declined.id)
        assertTrue(c.invites.value.isEmpty())
        c.inviteToChat(chat, admin.deviceId) // I own it: approved at once, nothing for me to act on
        assertTrue(c.invites.value.isEmpty())
    }

    @Test fun cacheSurvivesNewController() = runTest {
        val (api, chat) = fakeWithChat()
        api.incoming(chat, sam, "kept")
        controller(api).sync(chat)
        val offline = FakeRelayApi().apply { failAllWith = RelayException(RelayErrorKind.NETWORK, "down") }
        val again = ChatController({ offline }, ChatStore(dir), TestScope(UnconfinedTestDispatcher(testScheduler))) { "dev-1" }
        assertEquals(listOf("kept"), again.messages(chat).value.map { it.text })
        again.sync(chat) // offline: the cache stays
        assertEquals(listOf("kept"), again.messages(chat).value.map { it.text })
        // and a later sync only asks for what is newer
        api.incoming(chat, sam, "newer")
        val online = controller(api)
        online.sync(chat)
        assertEquals(1L, api.syncCalls.last().second)
        assertEquals(listOf("kept", "newer"), online.messages(chat).value.map { it.text })
    }

    @Test fun sendValidatesBeforePostingAndCountsCodePoints() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        val emoji = "😀" // one code point, two chars
        assertEquals(4000, c.send(chat, emoji.repeat(4000)).text!!.codePointCount(0, emoji.length * 4000))
        assertEquals("too_long", assertFailsWith<RelayException> { c.send(chat, "x".repeat(4001)) }.message)
        assertEquals("empty", assertFailsWith<RelayException> { c.send(chat, "  \n") }.message)
        assertEquals(1, api.postCalls)
        assertEquals(1, c.messages(chat).value.size) // the sent message shows at once
    }

    @Test fun editDeleteAndMembershipActions() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        val m = c.send(chat, "draft")
        c.edit(chat, m.id, "final")
        assertEquals("final", c.messages(chat).value.single().text)
        c.delete(chat, m.id)
        assertTrue(c.messages(chat).value.single { it.id == m.id }.deleted)
        c.transferChatOwner(chat, sam.deviceId)
        c.refresh()
        assertEquals(sam.deviceId, c.chats.value.single().ownerId)
        c.leaveChat(chat)
        assertTrue(c.chats.value.isEmpty()) // no longer visible to me
        assertTrue(c.messages(chat).value.isEmpty())
    }

    @Test fun refreshCoversAllSpacesAndDropsVanishedChats() = runTest {
        val (api, chat) = fakeWithChat()
        api.asMemberOf("s2", admin, name = "Work")
        val work = api.addChat("s2", "Standup", owner = api.me, members = listOf(api.me))
        val c = controller(api)
        c.refresh()
        assertEquals(setOf(chat, work), c.chats.value.map { it.id }.toSet())
        api.dropChat(work)
        c.refresh()
        assertEquals(listOf(chat), c.chats.value.map { it.id })
        assertNull(c.chats.value.firstOrNull { it.id == work })
    }

    @Test fun connectedEventCatchesUp() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        api.incoming(chat, sam, "missed")
        c.onEvent(RelayEvent.Connected)
        assertEquals(listOf("missed"), c.messages(chat).value.map { it.text })
    }
}
