@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.InviteState
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatsTabTest {
    private val me = Peer("me", "This PC", DeviceKind.PC)
    private val fred = Peer("fred", "Fred's phone", DeviceKind.PHONE)
    private val zed = Peer("zed", "Zed's laptop", DeviceKind.PC)
    private val link = "https://lakeside-cabins.example/booking/2214"
    private val at = 1_700_000_000_000L

    private fun chat(
        id: String = "c1", name: String = "Holiday planning", space: String = "Friends", spaceId: String = "s1",
        owner: String = "me", member: Boolean = true, last: ChatMessage? = null,
    ) = ChatSummary(id, spaceId, space, name, owner, rev = 5, readRev = 0, member = member, lastMessage = last)

    private fun msg(
        id: String, author: Peer, text: String?, rev: Long = 1, edited: Long? = null, deleted: Boolean = false,
        byAdmin: Boolean = false, event: ChatEvent? = null,
    ) = ChatMessage(id, "c1", author, rev, at + rev * 60_000, edited, deleted, byAdmin, text, event)

    private fun ui(
        chats: List<ChatSummary> = listOf(chat()), selected: String? = "c1", messages: List<ChatMessage> = emptyList(),
        invites: List<ChatInvite> = emptyList(), unread: Map<String, Int> = emptyMap(), adminOf: Set<String> = emptySet(),
        members: List<Peer> = listOf(me, fred),
    ) = ChatsUi(chats, invites, unread, "me", adminOf, selected, messages, members, { it.name }, notice = null)

    private fun androidx.compose.ui.test.ComposeUiTest.show(ui: ChatsUi, actions: ChatsActions = ChatsActions()) =
        setContent { MjolnirTheme { ChatsTabContent(ui, actions, now = { at }) } }

    @Test fun listGroupsBySpaceWithUnread() = runComposeUiTest {
        show(ui(
            chats = listOf(chat(), chat("c2", "Game night"), chat("c3", "Design review", "Work", "s2")),
            selected = null, unread = mapOf("c2" to 3),
        ))
        onNodeWithText("FRIENDS").assertExists()
        onNodeWithText("WORK").assertExists()
        onNodeWithText("Game night").assertExists()
        onNodeWithText("3").assertExists() // Game night's unread badge
    }

    @Test fun approvalCardShowsInviterAndName() = runComposeUiTest {
        val invite = ChatInvite("i1", "c1", "Holiday planning", "Friends", zed, fred, InviteState.PENDING_APPROVAL)
        val log = mutableListOf<String>()
        show(ui(invites = listOf(invite)), ChatsActions(onApprove = { log += "approve ${it.id}" }, onDeny = { log += "deny ${it.id}" }))
        onNodeWithText("Fred's phone wants to add Zed's laptop", substring = true).assertExists()
        onNodeWithText("Approve").performClick()
        onNodeWithText("Deny").performClick()
        assertEquals(listOf("approve i1", "deny i1"), log)
    }

    @Test fun invitationCardJoinDecline() = runComposeUiTest {
        val invite = ChatInvite("i2", "c9", "Book club", "Friends", me, fred, InviteState.APPROVED)
        val log = mutableListOf<String>()
        show(ui(selected = null, invites = listOf(invite)), ChatsActions(onJoin = { log += "join ${it.id}" }, onDecline = { log += "decline ${it.id}" }))
        onNodeWithText("You're invited to Book club", substring = true).assertExists()
        onNodeWithText("Join").performClick()
        onNodeWithText("Decline").performClick()
        assertEquals(listOf("join i2", "decline i2"), log)
    }

    @Test fun adminViewTag() = runComposeUiTest {
        show(ui(chats = listOf(chat(member = false, spaceId = "s2", space = "Work")), selected = null, adminOf = setOf("s2")))
        onNodeWithText("admin view").assertExists()
    }

    @Test fun adminOpeningAChatTheyAreNotInCanJoinInsteadOfTyping() = runComposeUiTest {
        var joined = 0
        show(ui(chats = listOf(chat(member = false)), adminOf = setOf("s1")), ChatsActions(onAdminJoin = { joined++ }))
        onNodeWithText("Message Holiday planning").assertDoesNotExist()
        onNodeWithText("Join").performClick()
        assertEquals(1, joined)
    }

    @Test fun ownMessageMenuHasEditDelete() = runComposeUiTest {
        show(ui(messages = listOf(msg("m1", me, "see $link"))))
        onNodeWithContentDescription("Message menu").performClick()
        onNodeWithText("Copy link").assertExists()
        onNodeWithText("Edit").assertExists()
        onNodeWithText("Delete").assertExists()
    }

    @Test fun othersMessageMenuHasCopyLinkOnly() = runComposeUiTest {
        val copied = mutableListOf<String>()
        show(ui(messages = listOf(msg("m1", fred, "Found it $link and https://second.example"))), ChatsActions(onCopy = { copied += it }))
        onNodeWithContentDescription("Message menu").performClick()
        onNodeWithText("Edit").assertDoesNotExist()
        onNodeWithText("Delete").assertDoesNotExist()
        onNodeWithText("Copy link").performClick()
        assertEquals(listOf(link), copied) // the first link
    }

    @Test fun messageWithoutLinkHasNoCopyLinkAndNoMenuForOthers() = runComposeUiTest {
        show(ui(messages = listOf(msg("m1", fred, "no links here"))))
        onNodeWithContentDescription("Message menu").assertDoesNotExist()
    }

    @Test fun adminSeesDeleteOnOthers() = runComposeUiTest {
        val deleted = mutableListOf<String>()
        show(ui(messages = listOf(msg("m1", fred, "hello")), adminOf = setOf("s1")), ChatsActions(onDeleteMessage = { deleted += it.id }))
        onNodeWithContentDescription("Message menu").performClick()
        onNodeWithText("Edit").assertDoesNotExist() // the admin cannot edit other people's text
        onNodeWithText("Delete").performClick()
        assertEquals(listOf("m1"), deleted)
    }

    @Test fun editedLabelShown() = runComposeUiTest {
        show(ui(messages = listOf(msg("m1", me, "Perfect", edited = at + 1))))
        onNodeWithText("(edited)", substring = true).assertExists()
    }

    @Test fun deletedShowsMessageDeleted() = runComposeUiTest {
        show(ui(messages = listOf(msg("m1", fred, null, deleted = true), msg("m2", fred, null, rev = 2, deleted = true, byAdmin = true))))
        onNodeWithText("Message deleted").assertExists()
        onNodeWithText("Deleted by the space admin").assertExists()
        onNodeWithContentDescription("Message menu").assertDoesNotExist()
    }

    @Test fun systemLinesAreCentredCopy() = runComposeUiTest {
        show(ui(messages = listOf(
            msg("e1", fred, null, 1, event = ChatEvent.MemberJoined("fred")),
            msg("e2", fred, null, 2, event = ChatEvent.MemberLeft("fred")),
            msg("e3", me, null, 3, event = ChatEvent.MemberRemoved("zed", byAdmin = true)),
            msg("e4", me, null, 4, event = ChatEvent.OwnerChanged("fred")),
            msg("e5", me, null, 5, event = ChatEvent.Renamed("Trip")),
        ), members = listOf(me, fred, zed)))
        onNodeWithText("Fred's phone joined").assertExists()
        onNodeWithText("Fred's phone left").assertExists()
        onNodeWithText("Zed's laptop · Removed by the space admin").assertExists()
        onNodeWithText("Fred's phone is now the owner").assertExists()
        onNodeWithText("Renamed to Trip").assertExists()
    }

    @Test fun composerPlaceholderNamesChat() = runComposeUiTest {
        show(ui())
        onNodeWithText("Message Holiday planning").assertExists()
    }

    @Test fun composerSendsTypedText() = runComposeUiTest {
        val sent = mutableListOf<String>()
        show(ui(), ChatsActions(onSend = { sent += it; true }))
        onNodeWithText("Send").performClick() // empty: nothing
        assertEquals(emptyList(), sent)
        onNode(hasSetTextAction()).performTextInput("  hello there ")
        onNodeWithText("Message Holiday planning").assertDoesNotExist() // the placeholder gives way to the text
        onNodeWithText("Send").performClick()
        waitForIdle()
        assertEquals(listOf("hello there"), sent)
    }

    @Test fun composerCapsAtFourThousandCodePoints() {
        val long = "a".repeat(3999) + "😀😀"
        val capped = capCodePoints(long, 4000)
        assertEquals(4000, capped.codePointCount(0, capped.length))
        assertEquals("a".repeat(3999) + "😀", capped) // never splits a surrogate pair
        assertEquals("short", capCodePoints("short", 4000))
    }

    @Test fun inviteCandidatesAreSpaceMembersNotInTheChat() {
        val space = Space("s1", "Friends", "admin", listOf(Peer("admin", "Admin", DeviceKind.PC), me, fred, zed))
        assertEquals(listOf("admin", "zed"), inviteCandidates(space, listOf(me, fred), "me").map { it.deviceId })
        assertNull(null)
    }

    // ---- settings ----

    private fun settings(
        owner: String = "me", members: List<Peer> = listOf(me, fred), admin: Boolean = false, invites: List<ChatInvite> = emptyList(),
        actions: ChatSettingsActions = ChatSettingsActions(),
    ) = ChatSettings(chat(owner = owner), members, invites, "me", admin, { it.name }, actions)

    @Test fun leaveDisabledForOwner() = runComposeUiTest {
        setContent { MjolnirTheme { ChatSettingsDialog(settings(), onClose = {}) } }
        onNodeWithText("Leave chat").assertIsNotEnabled() // hand ownership over first
        onNodeWithText("Delete chat").assertExists()
        onNodeWithText("Make owner").assertExists() // on Fred
    }

    @Test fun soleOwnerSeesDeleteInsteadOfLeave() = runComposeUiTest { // ruling CC-R5
        setContent { MjolnirTheme { ChatSettingsDialog(settings(members = listOf(me)), onClose = {}) } }
        onNodeWithText("Leave chat").assertDoesNotExist()
        onNodeWithText("Delete chat").assertExists()
    }

    @Test fun memberCanLeaveButNotDelete() = runComposeUiTest {
        val left = mutableListOf<String>()
        setContent { MjolnirTheme { ChatSettingsDialog(settings(owner = "fred", actions = ChatSettingsActions(onLeave = { left += "left" })), onClose = {}) } }
        onNodeWithText("Delete chat").assertDoesNotExist()
        onNodeWithText("Make owner").assertDoesNotExist()
        onNodeWithText("Leave chat").assertIsEnabled().performClick()
        onNodeWithText("Leave Holiday planning?").assertExists() // asks first
        assertEquals(emptyList(), left)
    }

    @Test fun adminCanRemoveMembersAndDelete() = runComposeUiTest {
        val removed = mutableListOf<String>()
        setContent { MjolnirTheme { ChatSettingsDialog(settings(owner = "fred", admin = true, actions = ChatSettingsActions(onRemove = { removed += it.deviceId })), onClose = {}) } }
        onNodeWithText("Delete chat").assertExists()
        onNodeWithText("Remove").performClick() // Fred; the admin is not asked to remove itself
        onNodeWithText("Remove Fred's phone from Holiday planning?").assertExists() // asks first
        assertEquals(emptyList(), removed)
        onNodeWithText("Remove").performClick()
        assertEquals(listOf("fred"), removed)
    }
}
