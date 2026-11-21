package app.mjolnir.android.ui

import androidx.lifecycle.SavedStateHandle
import app.mjolnir.android.ChatNotifier
import app.mjolnir.android.MainDispatcherRule
import app.mjolnir.client.ChatController
import app.mjolnir.client.ChatStore
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Nicknames
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.InviteState
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val api = FakeRelayApi()
    private val sam = Peer("sam", "Sam", DeviceKind.PHONE)
    private val ann = Peer("ann", "Ann", DeviceKind.PC)
    private val admin = Peer("admin", "Admin", DeviceKind.PC)
    private val foreground = MutableStateFlow(true)
    private val chatsVisible = MutableStateFlow(true)
    private val read = mutableListOf<String>()
    private lateinit var nicknames: Nicknames
    private lateinit var controller: ChatController
    private val me get() = api.me

    @Before fun setUp() {
        nicknames = Nicknames(JsonFileStore(File(tmp.root, "nicknames.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() })
        controller = ChatController({ api }, ChatStore(tmp.newFolder("chats")), CoroutineScope(main.dispatcher)) { me.deviceId }
        api.asMemberOf("s1", admin, name = "Crew")
        api.addMember("s1", sam)
        api.addMember("s1", ann)
    }

    private fun newVm(handle: SavedStateHandle = SavedStateHandle()) =
        ChatViewModel(controller, api, nicknames, { me.deviceId }, foreground, chatsVisible, handle, onRead = { read += it })

    private fun msg(id: String, author: Peer, text: String?, deleted: Boolean = false, byAdmin: Boolean = false) =
        app.mjolnir.protocol.ChatMessage(id, "c", author, 1, 1, text = text, deleted = deleted, deletedByAdmin = byAdmin)

    @Test fun listGroupsBySpaceWithUnread() = runTest {
        api.asMemberOf("s2", admin, name = "Work")
        val crew = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        val work = api.addChat("s2", "Design", owner = me)
        api.incoming(crew, sam, "one"); api.incoming(crew, sam, "two")
        nicknames.set("sam", "Samwise")
        val vm = newVm()
        controller.refresh(); advanceUntilIdle()
        val groups = vm.list.value.groups
        assertEquals(listOf("Crew", "Work"), groups.map { it.spaceName })
        val general = groups[0].rows.single()
        assertEquals(2, general.unread)
        assertEquals("Samwise: two", general.preview)
        val design = groups[1].rows.single()
        assertEquals(work, design.chat.id)
        assertEquals(0, design.unread)
        assertEquals("No messages yet", design.preview)
    }

    @Test fun joinDeclineInvitation() = runTest {
        val book = api.addChat("s1", "Book club", owner = sam)
        val film = api.addChat("s1", "Film club", owner = sam)
        api.invitedBy(sam, book, me.deviceId); api.invitedBy(sam, film, me.deviceId)
        val vm = newVm()
        vm.refresh(); advanceUntilIdle()
        val invitations = vm.list.value.invitations
        assertEquals(listOf("Book club", "Film club"), invitations.map { it.chatName })
        vm.join(invitations[0]); advanceUntilIdle()
        assertEquals(listOf(book), vm.list.value.groups.flatMap { it.rows }.map { it.chat.id })
        vm.decline(invitations[1]); advanceUntilIdle()
        assertTrue(vm.list.value.invitations.isEmpty())
        assertEquals(InviteState.DECLINED, api.chatState.invites.single { it.chatId == film }.state)
    }

    @Test fun approveDenyAsOwner() = runTest {
        val chat = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        api.invitedBy(sam, chat, ann.deviceId)
        val vm = newVm()
        vm.refresh(); advanceUntilIdle()
        val approval = vm.list.value.approvals.single()
        assertEquals("Ann", approval.invitee.name)
        vm.approve(approval); advanceUntilIdle()
        assertEquals(InviteState.APPROVED, api.chatState.invites.single().state)
        assertTrue(vm.list.value.approvals.isEmpty())

        val other = api.addChat("s1", "Other", owner = me, members = listOf(me, sam))
        api.invitedBy(sam, other, ann.deviceId)
        vm.refresh(); advanceUntilIdle()
        vm.deny(vm.list.value.approvals.single()); advanceUntilIdle()
        assertEquals(InviteState.DENIED, api.chatState.invites.single { it.chatId == other }.state)
    }

    @Test fun longPressActionsForOwnVsOthers() {
        val mine = msg("1", me, "hi")
        val theirs = msg("2", sam, "yo")
        assertEquals(listOf(MessageAction.SHARE, MessageAction.EDIT, MessageAction.DELETE), messageActions(mine, me.deviceId, isSpaceAdmin = false))
        assertEquals(listOf(MessageAction.SHARE), messageActions(theirs, me.deviceId, isSpaceAdmin = false))
        assertEquals(listOf(MessageAction.SHARE, MessageAction.DELETE), messageActions(theirs, me.deviceId, isSpaceAdmin = true))
        assertEquals(listOf(MessageAction.SHARE, MessageAction.EDIT, MessageAction.DELETE), messageActions(mine, me.deviceId, isSpaceAdmin = true))
        assertTrue(messageActions(msg("3", me, null, deleted = true), me.deviceId, isSpaceAdmin = true).isEmpty())
    }

    @Test fun editFlow() = runTest {
        val chat = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        val vm = newVm()
        vm.refresh(); vm.open(chat); advanceUntilIdle()
        vm.setDraft(chat, "first"); vm.send(); advanceUntilIdle()
        val sent = vm.messages.value.single { it.text == "first" }
        assertEquals("", vm.draft(chat).value)

        vm.setDraft(chat, "half typed")
        vm.startEdit(sent)
        assertEquals(sent.id, vm.editingId.value)
        assertEquals("first", vm.draft(chat).value)
        vm.setDraft(chat, "  second \n"); vm.send(); advanceUntilIdle() // trimmed like a send
        val edited = vm.messages.value.single { it.id == sent.id }
        assertEquals("second", edited.text)
        assertTrue(edited.editedAtEpochMs != null)
        assertNull(vm.editingId.value)
        assertEquals("half typed", vm.draft(chat).value) // the unsent text comes back

        vm.startEdit(edited); vm.cancelEdit()
        assertNull(vm.editingId.value)
        assertEquals("half typed", vm.draft(chat).value)
    }

    @Test fun refusedSendKeepsTheDraftAndSaysWhy() = runTest {
        val chat = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        val vm = newVm()
        vm.refresh(); vm.open(chat); advanceUntilIdle()
        api.chatState.rateLimited = true
        vm.setDraft(chat, "hello"); vm.send(); advanceUntilIdle()
        assertEquals("hello", vm.draft(chat).value)
        assertEquals("Too many messages. Wait a moment.", vm.notice.value)
        vm.dismissNotice()
        assertNull(vm.notice.value)
    }

    @Test fun deletedRendersMessageDeleted() = runTest {
        assertEquals("Message deleted", bodyText(msg("1", sam, null, deleted = true)))
        assertEquals("Deleted by the space admin", bodyText(msg("1", sam, null, deleted = true, byAdmin = true)))
        assertEquals("hi", bodyText(msg("1", sam, "hi")))
        val chat = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        val m = api.incoming(chat, sam, "oops")
        api.incomingDelete(chat, m.id)
        controller.sync(chat)
        assertEquals("Message deleted", bodyText(controller.messages(chat).value.single()))
    }

    @Test fun openChatMarksRead() = runTest {
        val chat = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        api.incoming(chat, sam, "a"); api.incoming(chat, sam, "b")
        val vm = newVm()
        vm.refresh(); advanceUntilIdle()
        assertEquals(2, controller.unread.value[chat])
        vm.open(chat); advanceUntilIdle()
        assertEquals(chat, controller.openChat.value)
        assertEquals(listOf(chat), read.distinct()) // the app is told, to drop the chat's notification
        assertEquals(api.chatState.chats.getValue(chat).rev, api.readRevOf(chat))
        assertTrue(controller.unread.value.isEmpty())
        foreground.value = false; advanceUntilIdle() // the app goes to the background: the chat is no longer on screen
        assertNull(controller.openChat.value)
        foreground.value = true
        vm.close(); advanceUntilIdle()
        assertNull(controller.openChat.value)
        assertNull(vm.selectedId.value)
    }

    @Test fun backgroundSyncNotifiesNewMessagesOnly() = runTest {
        val chat = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        api.incoming(chat, sam, "old")
        nicknames.set("sam", "Samwise")
        val posted = mutableListOf<Pair<String, String>>()
        ChatNotifier(controller, CoroutineScope(main.dispatcher), nicknames::get) { m, name -> posted += name to m.text }
        advanceUntilIdle()
        controller.refresh(); advanceUntilIdle()
        assertTrue(posted.isEmpty()) // history of a chat first seen is not news

        api.incoming(chat, sam, "new")
        api.chatState.post(chat, "mine") // our own message never notifies
        controller.refresh(); advanceUntilIdle()
        assertEquals(listOf("Samwise" to "new"), posted)
        controller.refresh(); advanceUntilIdle()
        assertEquals(1, posted.size) // nothing twice

        controller.openChat.value = chat // on screen: no notification
        api.incoming(chat, sam, "while open")
        controller.refresh(); advanceUntilIdle()
        assertEquals(1, posted.size)
    }

    @Test fun rotationKeepsDraft() = runTest {
        val chat = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        val handle = SavedStateHandle()
        val first = newVm(handle)
        first.open(chat)
        first.setDraft(chat, "half-typed")
        // The system saves and restores the handle across the new activity instance.
        val restored = SavedStateHandle(mapOf("chat" to handle.get<String>("chat"), "draft/$chat" to handle.get<String>("draft/$chat")))
        val second = newVm(restored)
        assertEquals(chat, second.selectedId.value)
        assertEquals("half-typed", second.draft(chat).value)
        assertEquals("half-typed", newVm(handle).draft(chat).value) // and the same handle, as after a rotation
        second.setDraft(chat, "x".repeat(Limits.MAX_CHAT_TEXT_LENGTH + 5))
        assertEquals(Limits.MAX_CHAT_TEXT_LENGTH, second.draft(chat).value.length)
        assertFalse(second.draft("other").value.isNotEmpty())
        advanceUntilIdle()
    }

    @Test fun leavingChatsTabReopensNotifications() = runTest {
        val chat = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        val posted = mutableListOf<String>()
        ChatNotifier(controller, CoroutineScope(main.dispatcher), nicknames::get) { m, _ -> posted += m.text }
        val vm = newVm()
        vm.refresh(); vm.open(chat); advanceUntilIdle()
        assertEquals(chat, controller.openChat.value)
        chatsVisible.value = false; advanceUntilIdle() // on another tab: the selected chat is not on screen
        assertNull(controller.openChat.value)
        api.incoming(chat, sam, "while on Throw")
        controller.refresh(); advanceUntilIdle()
        assertEquals(listOf("while on Throw"), posted)
        assertEquals(1, controller.unread.value[chat]) // and nothing was marked read behind the reader's back
        chatsVisible.value = true; advanceUntilIdle()
        assertEquals(chat, controller.openChat.value)
        assertTrue(controller.unread.value.isEmpty())
    }

    @Test fun closingMidEditRestoresTheDraft() = runTest {
        val chat = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        val vm = newVm()
        vm.refresh(); vm.open(chat); advanceUntilIdle()
        vm.setDraft(chat, "first"); vm.send(); advanceUntilIdle()
        val sent = vm.messages.value.single()
        vm.setDraft(chat, "unsent"); vm.startEdit(sent)
        vm.close()
        assertNull(vm.editingId.value)
        assertEquals("unsent", vm.draft(chat).value) // not the old message's text as a new draft
    }

    @Test fun doubleTapSendsOnce() = runTest {
        val chat = api.addChat("s1", "General", owner = me, members = listOf(me, sam))
        val vm = newVm()
        vm.refresh(); vm.open(chat); advanceUntilIdle()
        vm.setDraft(chat, "once"); vm.send(); vm.send(); advanceUntilIdle()
        assertEquals(1, api.postCalls)
        assertEquals("", vm.draft(chat).value)
    }
}
