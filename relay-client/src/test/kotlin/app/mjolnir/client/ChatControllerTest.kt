package app.mjolnir.client

import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
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
import kotlin.test.assertNotNull
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

    @Test fun clearRemovesCacheFiles() = runTest {
        val (api, chat) = fakeWithChat()
        val d = Files.createTempDirectory("chat-clear").toFile()
        val c = controller(api, ChatStore(d))
        c.refresh(); api.incoming(chat, sam, "hi"); c.sync(chat)
        c.openChat.value = chat
        assertTrue(d.listFiles { f -> f.name.startsWith("chat-") }!!.isNotEmpty())
        c.clear()
        assertEquals(emptyList(), d.listFiles { f -> f.name.startsWith("chat-") }!!.toList())
        assertEquals(emptyList(), c.chats.value); assertEquals(emptyList(), c.messages(chat).value)
        assertEquals(emptyMap(), c.unread.value); assertNull(c.openChat.value); assertEquals(emptyList(), c.invites.value)
        d.deleteRecursively()
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
        ChatStore(dir).update(chat) { it.copy(rev = stuck.rev, ownerId = api.me.deviceId, members = listOf(api.me, sam), messages = mapOf(stuck.id to stuck), name = "General", announcedRev = 0) }
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        c.sync(chat)
        assertEquals(listOf("stuck"), seen.map { it.text })
        c.sync(chat)
        assertEquals(1, seen.size)
    }

    @Test fun firstMessageAfterJoiningNotifiesViaRefresh() = runTest {
        val (api, _) = fakeWithChat()
        val book = api.addChat("s1", "Book club", owner = sam)
        api.incoming(book, sam, "history")
        val invite = api.invitedBy(sam, book, api.me.deviceId)
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        c.refresh()
        c.joinInvite(invite.id)
        assertTrue(seen.isEmpty()) // the history is not news
        api.incoming(book, sam, "hello new member")
        c.refresh() // the FCM -> InboxWorker path: no ChatChanged event reaches the phone
        assertEquals(listOf("hello new member"), seen.map { it.text })
    }

    @Test fun firstMessageAfterJoiningNotifiesEvenWhenTheJoinSyncFailed() = runTest {
        val (api, _) = fakeWithChat()
        val book = api.addChat("s1", "Book club", owner = sam)
        api.incoming(book, sam, "history")
        val invite = api.invitedBy(sam, book, api.me.deviceId)
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        c.refresh()
        api.failSyncOnCall = api.syncCalls.size + 1 // the sync right after joining fails
        c.joinInvite(invite.id)
        api.incoming(book, sam, "hello new member")
        c.refresh()
        assertEquals(listOf("hello new member"), seen.map { it.text })
        assertEquals(listOf("history", "hello new member"), c.messages(book).value.mapNotNull { it.text })
    }

    @Test fun failedFirstSyncThenRefreshAnnouncesNothing() = runTest {
        val (api, _) = fakeWithChat()
        val other = api.addChat("s1", "Old", owner = sam, members = listOf(sam, api.me))
        repeat(5) { api.incoming(other, sam, "history $it") }
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        api.failSyncOnCall = api.syncCalls.size + 1
        c.refresh() // lists the chats at their revs; the first sync fails, so the cache stays empty
        c.refresh()
        assertEquals(emptyList(), seen.map { it.text }) // history stays silent
    }

    // ---- Mentions and receipts ----

    @Test fun seenReportedAfterSync() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        val m = api.incoming(chat, sam, "hi")
        c.sync(chat)
        assertEquals(listOf(chat to m.rev), api.seenCalls)
        assertEquals(m.rev, api.seenRevOf(chat))
        c.sync(chat) // nothing new: nothing to report
        assertEquals(1, api.seenCalls.size)
        // Caught up through the doorbell and the refresh a push starts, too.
        val m2 = api.incoming(chat, sam, "again")
        c.refresh()
        assertEquals(listOf(chat to m.rev, chat to m2.rev), api.seenCalls)
        // A failed report is retried by the next refresh even though nothing new arrived.
        val other = api.addChat("s1", "Plans", owner = sam, members = listOf(sam, api.me))
        val m3 = api.incoming(other, sam, "x")
        api.failSeen = true
        c.refresh()
        assertEquals(0L, api.seenRevOf(other))
        api.failSeen = false
        c.refresh()
        assertEquals(m3.rev, api.seenRevOf(other))
        assertEquals(m3.rev, ChatStore(dir).read(other).seenRev)
    }

    @Test fun seenNeverReportedForAChatWhereIAmNotAMember() = runTest {
        val api = FakeRelayApi()
        api.spaceList += app.mjolnir.protocol.Space("s1", "Crew", api.me.deviceId, listOf(api.me, sam))
        val chat = api.addChat("s1", "Secret", owner = sam, members = listOf(sam))
        val m = api.incoming(chat, sam, "hi")
        val c = controller(api)
        c.refresh(); c.sync(chat)
        assertEquals(listOf("hi"), c.messages(chat).value.map { it.text }) // the admin sees it ...
        assertEquals(emptyList(), api.seenCalls)    // ... without ever counting as having seen it
        assertEquals(emptyList(), api.readCalls)
        assertEquals(listOf("sam"), c.receipts(chat).value.map { it.deviceId }) // and is not a receipt either
        assertEquals(m.rev, c.messages(chat).value.single().rev)
    }

    @Test fun readReportedOnlyWhenVisible() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        api.incoming(chat, sam, "while closed")
        c.sync(chat)
        assertEquals(emptyList(), api.readCalls) // synced and seen, never read
        assertEquals(0L, api.readRevOf(chat))
        c.openChat.value = chat // the apps set this only while the chat is on screen
        val m = api.incoming(chat, sam, "while visible")
        c.sync(chat)
        assertEquals(listOf(chat to m.rev), api.readCalls)
        assertEquals(m.rev, api.readRevOf(chat))
        assertTrue(c.unread.value.isEmpty())
        c.openChat.value = null
        api.incoming(chat, sam, "after leaving")
        c.sync(chat)
        assertEquals(1, api.readCalls.size)
        assertEquals(mapOf(chat to 1), c.unread.value)
    }

    @Test fun receiptsFollowSyncAndReceiptEvents() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val mine = c.send(chat, "hello")
        c.sync(chat)
        assertEquals(setOf("dev-1", "sam"), c.receipts(chat).value.map { it.deviceId }.toSet())
        val syncs = api.syncCalls.size
        c.onEvent(RelayEvent.ChatChanged(chat, mine.rev, receipts = listOf(api.incomingSeen(chat, sam, mine.rev)!!)))
        assertEquals(syncs, api.syncCalls.size) // the event carries the change: no sync request
        assertEquals(mine.rev, c.receipts(chat).value.single { it.deviceId == "sam" }.seenRev)
        c.onEvent(RelayEvent.ChatChanged(chat, mine.rev, receipts = listOf(api.incomingRead(chat, sam, mine.rev)!!)))
        assertEquals(syncs, api.syncCalls.size)
        val sams = c.receipts(chat).value.single { it.deviceId == "sam" }
        assertEquals(mine.rev, sams.readRev); assertTrue(sams.readAtEpochMs != null)
        // They survive a restart (the cache).
        assertEquals(sams, controller(api).receipts(chat).value.single { it.deviceId == "sam" })
    }

    @Test fun mentionIncomingFlagged() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        api.incoming(chat, sam, "plain")
        api.incoming(chat, sam, "hey @Me", mentions = listOf("dev-1"))
        api.incoming(chat, sam, "hey @Sam", mentions = listOf("sam"))
        c.sync(chat)
        assertEquals(listOf("plain" to false, "hey @Me" to true, "hey @Sam" to false), seen.map { it.text to it.mentionedMe })
        assertEquals(setOf(chat), c.unreadMentions.value)
        c.markRead(chat)
        assertEquals(emptySet(), c.unreadMentions.value)
        // Read on another device already: a plain message stays quiet, a mention still arrives.
        seen.clear()
        val a = api.incoming(chat, sam, "quiet"); val b = api.incoming(chat, sam, "@Me look", mentions = listOf("dev-1"))
        api.markChatRead(chat, b.rev)
        c.refresh()
        assertEquals(listOf("@Me look"), seen.map { it.text }); assertEquals(a.rev + 1, b.rev)
        // Not while the chat is open on this device.
        seen.clear()
        c.openChat.value = chat
        api.incoming(chat, sam, "@Me now", mentions = listOf("dev-1"))
        c.sync(chat)
        assertTrue(seen.isEmpty())
        // My own mention of myself and deleted mentions never count.
        c.openChat.value = null
        val own = c.send(chat, "note to @Me", listOf("dev-1")); c.sync(chat)
        val gone = api.incoming(chat, sam, "oops @Me", mentions = listOf("dev-1")); api.incomingDelete(chat, gone.id); c.sync(chat)
        assertEquals(emptySet(), c.unreadMentions.value); assertEquals(listOf("dev-1"), own.mentions)
    }

    @Test fun mentionCandidatesFilterByShownName() = runTest {
        val (api, chat) = fakeWithChat()
        api.addMember("s1", Peer("zed", "Zed", DeviceKind.PHONE))
        val c = controller(api)
        c.sync(chat)
        assertEquals(listOf("sam"), c.mentionCandidates(chat, "").map { it.deviceId }) // never me
        assertEquals(listOf("sam"), c.mentionCandidates(chat, "SA").map { it.deviceId })
        assertEquals(listOf("sam"), c.mentionCandidates(chat, "buddy") { if (it.deviceId == "sam") "Buddy" else it.name }.map { it.deviceId })
        assertEquals(emptyList(), c.mentionCandidates(chat, "buddy"))
    }

    @Test fun infoTimesArePerMessage() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val m1 = c.send(chat, "one"); val m2 = c.send(chat, "two")
        api.incomingSeen(chat, sam, m1.rev); api.incomingRead(chat, sam, m1.rev)
        api.incomingSeen(chat, sam, m2.rev)
        val first = c.messageReceipts(chat, m1.id).single { it.deviceId == "sam" }
        val second = c.messageReceipts(chat, m2.id).single { it.deviceId == "sam" }
        assertTrue(first.readAtEpochMs != null && first.seenAtEpochMs != null)
        assertNull(second.readAtEpochMs) // not read yet
        assertTrue(second.seenAtEpochMs!! > first.seenAtEpochMs!!, "the later message was seen later") // not Sam's latest time on both
    }

    @Test fun editedMessageKeepsItsReceipts() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val m = c.send(chat, "typo")
        c.onEvent(RelayEvent.ChatChanged(chat, m.rev, receipts = listOf(api.incomingRead(chat, sam, m.rev)!!)))
        val edited = c.edit(chat, m.id, "fixed")
        assertTrue(edited.rev > m.rev); assertEquals(m.rev, edited.createdRev)
        c.sync(chat)
        val shown = c.messages(chat).value.single { it.id == m.id }
        assertEquals("Read by everyone", receiptLineFor(shown, c.receipts(chat).value, c.members(chat).value))
        assertEquals(m.rev, c.messageReceipts(chat, m.id).let { shown.createdRev }) // the fake relay also times it by the first rev
        assertTrue(c.messageReceipts(chat, m.id).single { it.deviceId == "sam" }.readAtEpochMs != null)
    }

    @Test fun mentionAddedByAnEditIsAnnounced() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        val m = api.incoming(chat, sam, "psst")
        c.sync(chat)
        assertEquals(listOf(false), seen.map { it.mentionedMe }); seen.clear()
        api.incomingEdit(chat, m.id, "psst @Me", mentions = listOf("dev-1"))
        c.onEvent(RelayEvent.ChatChanged(chat, api.chatState.chats.getValue(chat).rev, mentioned = true))
        assertEquals(listOf("psst @Me" to true), seen.map { it.text to it.mentionedMe })
        // An edit that keeps an existing mention is not news again, and not while the chat is open here.
        api.incomingEdit(chat, m.id, "psst @Me!", mentions = listOf("dev-1")); c.sync(chat)
        assertEquals(1, seen.size)
        c.openChat.value = chat
        val m2 = api.incoming(chat, sam, "x"); c.sync(chat); seen.clear()
        api.incomingEdit(chat, m2.id, "x @Me", mentions = listOf("dev-1")); c.sync(chat)
        assertTrue(seen.isEmpty())
    }

    @Test fun adminOverseeingAChatIsNotToldOfItsMessages() = runTest {
        val api = FakeRelayApi()
        api.asMemberOf("s1", api.me, name = "Crew") // I am the space admin, not in the chat
        api.addMember("s1", sam)
        val chat = api.addChat("s1", "Private", owner = sam, members = listOf(sam))
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        c.refresh()
        assertFalse(c.chats.value.single().member)
        val m = api.incoming(chat, sam, "members only")
        c.onEvent(RelayEvent.ChatChanged(chat, m.rev))
        assertEquals(listOf("members only"), c.messages(chat).value.map { it.text }) // overseen, not announced
        assertTrue(seen.isEmpty(), "got $seen")
    }

    @Test fun anEditDoesNotMakeAReadMessageUnread() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        val m = api.incoming(chat, sam, "hi @Me", mentions = listOf("dev-1"))
        c.refresh()
        assertEquals(mapOf(chat to 1), c.unread.value); assertEquals(setOf(chat), c.unreadMentions.value)
        c.markRead(chat)
        api.incomingEdit(chat, m.id, "hi @Me!")
        c.sync(chat)
        assertTrue(c.unread.value.isEmpty()); assertTrue(c.unreadMentions.value.isEmpty())
    }

    @Test fun anEditOfAMessageReadElsewhereIsNotAnnounced() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh() // listed empty: its first messages are news
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        val m = api.incoming(chat, sam, "read on the phone")
        api.markChatRead(chat, m.rev)
        api.incomingEdit(chat, m.id, "read on the phone, edited")
        c.refresh()
        assertEquals(listOf("read on the phone, edited"), c.messages(chat).value.map { it.text })
        assertTrue(seen.isEmpty(), "got $seen")
        assertTrue(c.unread.value.isEmpty())
    }

    @Test fun firstMessageAfterARestartIsAnnouncedForAChatListedEmpty() = runTest {
        val (api, chat) = fakeWithChat()
        controller(api).refresh() // listed empty, then the process dies
        val c = controller(api) // same store directory, new process
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        api.incoming(chat, sam, "first!")
        c.refresh()
        assertEquals(listOf("first!"), seen.map { it.text })
        assertFalse(ChatStore(dir).read(chat).listedEmpty) // once messages are synced it is history
        // A chat created here, empty, is remembered the same way.
        val made = c.createChat("s1", "Fresh").id
        val c2 = controller(api)
        val seen2 = mutableListOf<IncomingMessage>(); collect(c2, seen2)
        api.incoming(made, sam, "hello there")
        c2.refresh()
        assertEquals(listOf("hello there"), seen2.map { it.text })
    }

    @Test fun openingAnEmptyChatKeepsItsFirstMessagesNewsAfterARestart() = runTest {
        val (api, chat) = fakeWithChat()
        val first = controller(api)
        first.refresh() // listed empty
        first.sync(chat) // opened while still empty, then the process dies
        assertTrue(ChatStore(dir).read(chat).listedEmpty)
        val c = controller(api)
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        api.incoming(chat, sam, "first!")
        c.refresh()
        assertEquals(listOf("first!"), seen.map { it.text })
        // A chat created here and opened at once is remembered the same way.
        val made = c.createChat("s1", "Fresh").id
        c.sync(made)
        val c2 = controller(api)
        val seen2 = mutableListOf<IncomingMessage>(); collect(c2, seen2)
        api.incoming(made, sam, "hello there")
        c2.refresh()
        assertEquals(listOf("hello there"), seen2.map { it.text })
    }

    @Test fun listPreviewIgnoresEdits() = runTest {
        val (api, chat) = fakeWithChat()
        val first = api.incoming(chat, sam, "first"); api.incoming(chat, sam, "second")
        val c = controller(api)
        c.refresh()
        api.incomingEdit(chat, first.id, "first, edited")
        c.sync(chat)
        assertEquals("second", c.chats.value.single().lastMessage?.text)
    }

    @Test fun aSyncSuspendedAcrossClearWritesAndAnnouncesNothing() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        api.incoming(chat, sam, "in flight")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { api.chatState.syncGate = it }
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.sync(chat) } // suspends in the fake relay
        c.clear()
        gate.complete(Unit)
        job.join()
        assertTrue(seen.isEmpty(), "got $seen")
        assertTrue(c.messages(chat).value.isEmpty()); assertTrue(c.chats.value.isEmpty())
        assertEquals(emptySet(), ChatStore(dir).cachedIds())
    }

    // ---- Media ----

    private val mediaDir: File = Files.createTempDirectory("chat-media").toFile().also { it.deleteOnExit() }
    private val picked: File = Files.createTempDirectory("picked").toFile().also { it.deleteOnExit() }

    private fun TestScope.mediaController(
        api: RelayApi,
        scope: CoroutineScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
    ) = ChatController(
        { api }, ChatStore(dir), scope,
        outbox = ChatOutbox(File(mediaDir, "outbox"), chunkBytes = 4),
        media = ChatMediaCache(File(mediaDir, "cache")),
    ) { "dev-1" }

    private fun pick(name: String, size: Int): File = File(picked, name).apply { parentFile.mkdirs(); writeBytes(ByteArray(size) { (it * 5 + name.length).toByte() }) }

    private fun pickPng(name: String, w: Int = 40, h: Int = 30): File = File(picked, name).apply {
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", this)
    }

    /** [fake], except that upload chunk number [failChunk] (1-based, counting all) fails with a network error. */
    private class FlakyUploads(val fake: FakeRelayApi, var failChunk: Int? = null) : RelayApi by fake {
        var chunks = 0
        override suspend fun uploadChunk(uploadId: String, start: Long, total: Long, bytes: ByteArray, length: Int): app.mjolnir.protocol.UploadStatus {
            if (++chunks == failChunk) throw RelayException(RelayErrorKind.NETWORK, "down")
            return fake.uploadChunk(uploadId, start, total, bytes, length)
        }
    }

    @Test fun messagePostedOnlyAfterAllUploads() = runTest {
        val (fake, chat) = fakeWithChat()
        var finishedAtPost: Int? = null
        val api = object : RelayApi by FlakyUploads(fake, failChunk = 4) {
            override suspend fun postMessage(chatId: String, text: String?, mentions: List<String>, attachments: List<Attachment>): ChatMessage {
                assertTrue(fake.sessions.isEmpty(), "an upload is still open when posting")
                finishedAtPost = fake.uploads.size
                return fake.postMessage(chatId, text, mentions, attachments)
            }
        }
        val c = mediaController(api)
        val png = pickPng("photo.png"); val txt = pick("notes.txt", 10); val bin = pick("data.bin", 12)
        c.send(chat, "look", listOf(png, txt, bin), ImageIoThumbnailer)
        c.awaitSendsIdle()
        // Interrupted halfway: nothing posted, the send stays on screen with its progress.
        assertEquals(0, fake.postCalls)
        val pending = c.pendingSends.value.single()
        assertEquals(chat, pending.chatId); assertEquals("look", pending.text); assertNull(pending.failure)
        assertEquals(listOf("photo.png", "notes.txt", "data.bin"), pending.files.map { it.name })
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.FILE, AttachmentKind.FILE), pending.files.map { it.kind })
        assertTrue(pending.sentBytes in 1 until pending.totalBytes, "progress ${pending.sentBytes}/${pending.totalBytes}")
        c.resumeSends().join()
        assertEquals(1, fake.postCalls)
        assertEquals(4, finishedAtPost) // three files and the photo's thumbnail
        val posted = c.messages(chat).value.single { it.author.deviceId == "dev-1" }
        assertEquals("look", posted.text)
        assertEquals(listOf("photo.png", "notes.txt", "data.bin"), posted.attachments.map { it.name })
        val photo = posted.attachments.first()
        assertEquals(AttachmentKind.IMAGE, photo.kind); assertEquals(40 to 30, photo.width to photo.height)
        assertNotNull(photo.thumbFileId); assertNull(photo.relativePath)
        assertEquals(bin.readBytes().toList(), fake.blobs.getValue(posted.attachments[2].fileId).toList())
        assertTrue(c.pendingSends.value.isEmpty())
        assertTrue(File(mediaDir, "outbox").walk().none { it.isFile && it.extension == "jpg" }, "thumbnails cleaned up")
    }

    @Test fun uploadResumesAfterRestart() = runTest {
        val (fake, chat) = fakeWithChat()
        val c1 = mediaController(FlakyUploads(fake, failChunk = 2))
        c1.send(chat, null, listOf(pick("big.bin", 10)), ImageIoThumbnailer)
        c1.awaitSendsIdle()
        assertEquals(0, fake.postCalls); assertEquals(1, fake.sessions.size)
        // The app restarts: the send is still shown, then resumes the same upload session and posts.
        val c2 = mediaController(fake)
        assertEquals(listOf("big.bin"), c2.pendingSends.value.single().files.map { it.name })
        c2.onEvent(RelayEvent.Connected)
        c2.awaitSendsIdle()
        assertEquals(1, fake.postCalls)
        assertEquals(listOf("up-1"), fake.uploads.map { it.id }) // no second session
        val posted = fake.chatState.sync(chat, 0).messages.single { it.author.deviceId == "dev-1" }
        assertNull(posted.text); assertEquals(listOf("up-1"), posted.attachments.map { it.fileId })
        assertTrue(c2.pendingSends.value.isEmpty())
        assertEquals(posted.id, c2.messages(chat).value.last().id)
    }

    @Test fun folderAndMoreThanTwentyFilesSplitIntoMessages() = runTest {
        val (fake, chat) = fakeWithChat()
        val c = mediaController(fake)
        val folder = File(picked, "Album").apply { mkdirs() }
        repeat(43) { File(folder, "sub/p%02d.txt".format(it)).apply { parentFile.mkdirs(); writeText("$it") } }
        val single = pick("readme.md", 3)
        c.send(chat, "trip @Sam", listOf(single, folder), ImageIoThumbnailer, mentions = listOf("sam"))
        c.awaitSendsIdle()
        val mine = c.messages(chat).value.filter { it.author.deviceId == "dev-1" }
        assertEquals(listOf(20, 20, 4), mine.map { it.attachments.size })
        assertEquals(listOf("trip @Sam", null, null), mine.map { it.text })
        assertEquals(listOf(listOf("sam"), emptyList(), emptyList()), mine.map { it.mentions })
        val all = mine.flatMap { it.attachments }
        assertEquals(listOf(null) + (0 until 43).map { "Album/sub/p%02d.txt".format(it) }, all.map { it.relativePath })
        val groups = groupAttachments(mine.first().attachments)
        assertEquals(AttachmentGroup.Single(all.first()), groups.first())
        val album = groups[1] as AttachmentGroup.Folder
        assertEquals("Album", album.name); assertEquals(19, album.count); assertEquals(19L * 1 + 9, album.totalBytes)
        assertEquals("Folder · 19 files · 28 B", Messages.folderDetail(album.count, album.totalBytes))
    }

    @Test fun expiredUploadIsUploadedAgainOnce() = runTest {
        val (fake, chat) = fakeWithChat()
        var expireOnPost = 1
        val api = object : RelayApi by fake {
            override suspend fun postMessage(chatId: String, text: String?, mentions: List<String>, attachments: List<Attachment>): ChatMessage {
                if (expireOnPost-- > 0) fake.expire(attachments.first().fileId) // purged by the relay's 1-hour rule
                return fake.postMessage(chatId, text, mentions, attachments)
            }
        }
        val c = mediaController(api)
        c.send(chat, null, listOf(pick("a.bin", 5), pick("b.bin", 5)), ImageIoThumbnailer)
        c.awaitSendsIdle()
        assertEquals(2, fake.postCalls)
        assertEquals(listOf("up-2", "up-3"), fake.uploads.map { it.id }) // a (up-1) expired: b, then a again; b kept
        val posted = c.messages(chat).value.single { it.author.deviceId == "dev-1" }
        assertEquals(listOf("a.bin", "b.bin"), posted.attachments.map { it.name })
        assertTrue(c.pendingSends.value.isEmpty())
        // Expired again after the second upload: the send fails instead of looping.
        expireOnPost = 2
        c.send(chat, null, listOf(pick("c.bin", 5)), ImageIoThumbnailer)
        c.awaitSendsIdle()
        assertEquals(4, fake.postCalls)
        val failed = c.pendingSends.value.single()
        assertEquals("Not sent: a file expired on the relay.", failed.failure)
        c.dismissSend(failed.id)
        assertTrue(c.pendingSends.value.isEmpty())
        assertTrue(ChatOutbox(File(mediaDir, "outbox")).pending().isEmpty())
    }

    @Test fun lostPostResponseIsNotPostedTwice() = runTest {
        val (fake, chat) = fakeWithChat()
        var lose = true
        val api = object : RelayApi by fake {
            override suspend fun postMessage(chatId: String, text: String?, mentions: List<String>, attachments: List<Attachment>): ChatMessage =
                fake.postMessage(chatId, text, mentions, attachments).also { if (lose) { lose = false; throw RelayException(RelayErrorKind.NETWORK, "reset") } }
        }
        val c = mediaController(api)
        c.send(chat, "once", listOf(pick("a.bin", 5)), ImageIoThumbnailer)
        c.awaitSendsIdle()
        assertEquals(1, c.pendingSends.value.size)
        c.resumeSends().join()
        assertTrue(c.pendingSends.value.isEmpty())
        assertEquals(1, fake.uploads.size)
        assertEquals(listOf("once"), c.messages(chat).value.filter { it.author.deviceId == "dev-1" }.map { it.text })
    }

    @Test fun permanentUploadFailureShowsWhy() = runTest {
        val (fake, chat) = fakeWithChat()
        fake.failUploadsWith = RelayException(RelayErrorKind.STORAGE_FULL, "relay_full")
        val c = mediaController(fake)
        c.send(chat, "hi", listOf(pick("a.bin", 5)), ImageIoThumbnailer)
        c.awaitSendsIdle()
        assertEquals("Not sent: the relay is out of space.", c.pendingSends.value.single().failure)
        assertEquals(0, fake.postCalls)
    }

    @Test fun failedSendSurvivesARestartUntilDismissed() = runTest {
        val (fake, chat) = fakeWithChat()
        var posts = 0
        val api = object : RelayApi by fake {
            override suspend fun postMessage(chatId: String, text: String?, mentions: List<String>, attachments: List<Attachment>): ChatMessage {
                if (++posts == 2) throw RelayException(RelayErrorKind.TOO_LARGE, "too_large") // the second batch fails for good
                return fake.postMessage(chatId, text, mentions, attachments)
            }
        }
        val c1 = mediaController(api)
        val files = (0 until 24).map { pick("f%02d.bin".format(it), 3) } + pickPng("last.png")
        c1.send(chat, "split", files, ImageIoThumbnailer)
        c1.awaitSendsIdle()
        assertEquals(listOf(20), c1.messages(chat).value.filter { it.author.deviceId == "dev-1" }.map { it.attachments.size })
        val failure = "Not sent: a file is too big for the relay."
        // The process dies before anyone looks: a new controller still shows the failed rest of the send.
        val c2 = mediaController(api)
        val failed = c2.pendingSends.value.single()
        assertEquals(failure, failed.failure); assertNull(failed.text)
        assertEquals(listOf("f20.bin", "f21.bin", "f22.bin", "f23.bin", "last.png"), failed.files.map { it.name })
        val thumb = File(assertNotNull(failed.files.last().thumbPath))
        assertTrue(thumb.isFile, "the failed send keeps its thumbnail")
        // Never retried.
        c2.resumeSends().join(); c2.onEvent(RelayEvent.Connected); c2.awaitSendsIdle()
        assertEquals(2, posts)
        assertEquals(failure, c2.pendingSends.value.single().failure)
        c2.dismissSend(failed.id)
        assertTrue(c2.pendingSends.value.isEmpty())
        assertTrue(ChatOutbox(File(mediaDir, "outbox")).pending().isEmpty()); assertFalse(thumb.exists())
        assertTrue(mediaController(api).pendingSends.value.isEmpty())
    }

    @Test fun dismissIgnoresASendInProgress() = runTest {
        val (fake, chat) = fakeWithChat()
        val c = mediaController(FlakyUploads(fake, failChunk = 1))
        c.send(chat, null, listOf(pick("a.bin", 5)), ImageIoThumbnailer)
        c.awaitSendsIdle()
        val pending = c.pendingSends.value.single()
        c.dismissSend(pending.id)
        assertEquals(listOf(pending.id), c.pendingSends.value.map { it.id })
        assertEquals(1, ChatOutbox(File(mediaDir, "outbox")).pending().size)
    }

    @Test fun clearDropsPendingSends() = runTest {
        val (fake, chat) = fakeWithChat()
        val flaky = FlakyUploads(fake, failChunk = 1)
        val c = mediaController(flaky)
        c.send(chat, null, listOf(pick("a.bin", 5)), ImageIoThumbnailer)
        c.awaitSendsIdle()
        assertEquals(1, c.pendingSends.value.size)
        c.clear()
        assertTrue(c.pendingSends.value.isEmpty())
        c.resumeSends().join()
        assertEquals(0, fake.postCalls)
        assertTrue(ChatOutbox(File(mediaDir, "outbox")).pending().isEmpty())
    }

    @Test fun sendRejectsNothingToSend() = runTest {
        val (fake, chat) = fakeWithChat()
        val c = mediaController(fake)
        assertEquals("empty", assertFailsWith<RelayException> { c.send(chat, "  ", emptyList(), ImageIoThumbnailer) }.message)
        assertEquals("empty", assertFailsWith<RelayException> { c.send(chat, null, listOf(File(picked, "Empty").apply { mkdirs() }), ImageIoThumbnailer) }.message)
        c.send(chat, "just text", emptyList(), ImageIoThumbnailer)
        assertEquals(listOf("just text"), c.messages(chat).value.map { it.text })
        assertTrue(c.pendingSends.value.isEmpty())
    }

    @Test fun downloadCopiesFromTheCache() = runTest {
        val (fake, chat) = fakeWithChat()
        val bytes = ByteArray(9) { it.toByte() }
        fake.putBlob("f-1", "doc.pdf", bytes)
        fake.incoming(chat, sam, null, attachments = listOf(Attachment("f-1", AttachmentKind.FILE, "doc.pdf", 9, "application/pdf")))
        val c = mediaController(fake)
        c.sync(chat)
        val att = c.messages(chat).value.single().attachments.single()
        val saveDir = Files.createTempDirectory("saved").toFile()
        c.download(chat, att, File(saveDir, "doc.pdf"))
        c.download(chat, att, File(saveDir, "doc (1).pdf"))
        assertEquals(listOf("f-1"), fake.chatDownloads) // the second copy came from the cache
        assertEquals(setOf("doc.pdf", "doc (1).pdf"), saveDir.list()!!.toSet())
        assertEquals(bytes.toList(), File(saveDir, "doc (1).pdf").readBytes().toList())
    }

    @Test fun downloadOfDeletedMessageFails() = runTest {
        val (fake, chat) = fakeWithChat()
        fake.putBlob("f-1", "doc.pdf", ByteArray(9))
        val m = fake.incoming(chat, sam, "the doc", attachments = listOf(Attachment("f-1", AttachmentKind.FILE, "doc.pdf", 9, "application/pdf")))
        val c = mediaController(fake)
        c.sync(chat)
        val att = c.messages(chat).value.single().attachments.single()
        fake.incomingDelete(chat, m.id) // the relay frees the file at once
        val saveDir = Files.createTempDirectory("saved").toFile()
        val e = assertFailsWith<RelayException> { c.download(chat, att, File(saveDir, "doc.pdf")) }
        assertEquals(Messages.MESSAGE_DELETED, e.userMessage); assertEquals(RelayErrorKind.NOT_FOUND, e.kind)
        assertTrue(saveDir.list()!!.isEmpty(), "left ${saveDir.list()!!.toList()}")
        assertTrue(c.messages(chat).value.single().deleted) // the chat caught up with the deletion
        // A 404 halfway through a download (deleted meanwhile) leaves no partial file either.
        fake.putBlob("f-2", "b.bin", ByteArray(9))
        val halfway = object : RelayApi by fake {
            override suspend fun downloadChatFile(fileId: String, target: File, onProgress: (Long) -> Unit) {
                target.writeBytes(ByteArray(4)); throw RelayException(RelayErrorKind.NOT_FOUND, "not_found")
            }
        }
        val c2 = mediaController(halfway)
        val e2 = assertFailsWith<RelayException> { c2.download(chat, att.copy(fileId = "f-2"), File(saveDir, "b.bin")) }
        assertEquals(Messages.MESSAGE_DELETED, e2.userMessage)
        assertTrue(saveDir.list()!!.isEmpty())
        assertTrue(File(mediaDir, "cache").list().orEmpty().isEmpty(), "cache left ${File(mediaDir, "cache").list()?.toList()}")
    }

    @Test fun notificationLabelsForMedia() = runTest {
        val (api, chat) = fakeWithChat()
        val c = controller(api)
        c.refresh()
        val seen = mutableListOf<IncomingMessage>(); collect(c, seen)
        fun att(kind: AttachmentKind) = Attachment("f-$kind", kind, "x", 1, "application/octet-stream")
        for (kind in listOf(AttachmentKind.IMAGE, AttachmentKind.GIF, AttachmentKind.VIDEO, AttachmentKind.FILE)) {
            api.incoming(chat, sam, null, attachments = listOf(att(kind)))
        }
        api.incoming(chat, sam, "with a caption", attachments = listOf(att(AttachmentKind.IMAGE)))
        c.sync(chat)
        assertEquals(listOf("Photo", "GIF", "Video", "File", "with a caption"), seen.map { it.text })
        assertEquals("with a caption", Messages.messagePreview(c.chats.value.single().lastMessage!!))
        api.incoming(chat, sam, null, attachments = listOf(att(AttachmentKind.GIF), att(AttachmentKind.IMAGE)))
        c.sync(chat)
        assertEquals("GIF", Messages.messagePreview(c.chats.value.single().lastMessage!!))
        assertEquals("GIF", seen.last().text)
    }
}
