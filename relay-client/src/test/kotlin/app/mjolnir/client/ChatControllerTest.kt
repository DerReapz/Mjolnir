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
        assertEquals(listOf(IncomingMessage(chat, "General", "sam", "Sam", "while closed")), seen)
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

    private fun TestScope.collect(c: ChatController, into: MutableList<IncomingMessage>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.newMessages.collect { into += it } }
    }

    @Test fun firstMessageInNewlyVisibleChatNotifies() = runTest {
        val (api, _) = fakeWithChat()
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        c.refresh()
        val plans = api.addChat("s1", "Plans", owner = sam, members = listOf(sam, api.me))
        api.incoming(plans, sam, "old 1"); api.incoming(plans, sam, "old 2")
        val first = api.incoming(plans, sam, "news")
        c.onEvent(RelayEvent.ChatChanged(plans, first.rev))
        assertEquals(listOf(IncomingMessage(plans, "Plans", "sam", "Sam", "news")), seen) // below the event's rev is history
        assertEquals(3, c.messages(plans).value.size)
    }

    @Test fun firstMessageAloneInNewlyVisibleChatNotifies() = runTest {
        val (api, _) = fakeWithChat()
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        val plans = api.addChat("s1", "Plans", owner = sam, members = listOf(sam, api.me))
        val first = api.incoming(plans, sam, "hello")
        c.onEvent(RelayEvent.ChatChanged(plans, first.rev))
        assertEquals(listOf("hello"), seen.map { it.text })
    }

    @Test fun joinedChatHistoryIsSilent() = runTest {
        val (api, _) = fakeWithChat()
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        val plans = api.addChat("s1", "Plans", owner = sam, members = listOf(sam))
        api.incoming(plans, sam, "before you came")
        val invite = api.invitedBy(sam, plans, api.me.deviceId)
        c.refreshInvites()
        c.joinInvite(invite.id)
        assertTrue(c.messages(plans).value.any { it.text == "before you came" })
        assertTrue(seen.isEmpty(), "got $seen")
        val m = api.incoming(plans, sam, "after")
        c.onEvent(RelayEvent.ChatChanged(plans, m.rev))
        assertEquals(listOf("after"), seen.map { it.text })
    }

    @Test fun freshControllerWithExistingCacheNotifiesFromRefresh() = runTest {
        val (api, chat) = fakeWithChat()
        api.incoming(chat, sam, "seen already")
        controller(api).sync(chat)
        api.incoming(chat, sam, "while the app was closed")
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        c.refresh()
        assertEquals(listOf("while the app was closed"), seen.map { it.text })
    }

    @Test fun unreadAndAnnouncementsWorkBeforeTheFirstRefresh() = runTest {
        val (api, chat) = fakeWithChat()
        api.incoming(chat, sam, "a"); api.incoming(chat, sam, "b")
        api.markChatRead(chat, 1)
        controller(api).refresh() // caches the chat with its name and read marker
        val offline = FakeRelayApi().apply { failAllWith = RelayException(RelayErrorKind.NETWORK, "down") }
        val restarted = ChatController({ offline }, ChatStore(dir), TestScope(UnconfinedTestDispatcher(testScheduler))) { "dev-1" }
        assertEquals(mapOf(chat to 1), restarted.unread.value) // only "b" is above the persisted read marker
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        api.incoming(chat, sam, "c")
        c.sync(chat) // no refresh yet: the name comes from the cache
        assertEquals(listOf(IncomingMessage(chat, "General", "sam", "Sam", "c")), seen)
    }

    @Test fun aBurstOfMessagesIsNotLost() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val seen = mutableListOf<IncomingMessage>()
        backgroundScope.launch { c.newMessages.collect { seen += it } } // runs only when the test yields
        testScheduler.runCurrent()
        repeat(200) { api.incoming(chat, sam, "m$it") }
        c.sync(chat)
        kotlinx.coroutines.delay(10) // lets the collector drain what is still buffered
        assertEquals((0 until 200).map { "m$it" }, seen.map { it.text })
    }

    @Test fun pagesFetchedBeforeAFailureAreAnnouncedOnceAndTheRestOnRetry() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        repeat(450) { api.incoming(chat, sam, "m$it") }
        api.failSyncOnCall = api.syncCalls.size + 2 // page 2 fails
        c.sync(chat)
        assertEquals(200, seen.size)
        api.failSyncOnCall = null
        c.sync(chat)
        assertEquals((0 until 450).map { "m$it" }, seen.map { it.text }) // no gaps, no repeats
    }

    @Test fun messagesCachedButNeverAnnouncedAreAnnouncedOnTheNextSync() = runTest {
        val (api, chat) = fakeWithChat()
        // As left behind by a sync that died between caching a page and announcing it.
        val stuck = api.incoming(chat, sam, "stuck")
        ChatStore(dir).update(chat) { it.copy(rev = stuck.rev, ownerId = api.me.deviceId, messages = mapOf(stuck.id to stuck), name = "General", announcedRev = 0) }
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        c.sync(chat)
        assertEquals(listOf("stuck"), seen.map { it.text })
        c.sync(chat)
        assertEquals(1, seen.size)
    }
}
