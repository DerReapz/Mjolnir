package app.mjolnir.client

import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.InviteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each chat call against the real relay routes: paths, bodies and error mapping. */
class RelayClientChatTest {
    private class Crew(val admin: TestDevice, val ann: TestDevice, val bob: TestDevice, val spaceId: String)

    private suspend fun ClientTestScope.join(admin: TestDevice, spaceId: String, guest: TestDevice) {
        val claim = guest.api.claimPairingCode(admin.api.createInvite(spaceId).code, CodeKind.SPACE_INVITE)
        admin.api.acceptPairing(claim.pairingId)
    }

    /** Admin's space "Crew" with members Ann and Bob. */
    private suspend fun ClientTestScope.crew(): Crew {
        val admin = newDevice("Admin", DeviceKind.PC)
        val ann = newDevice("Ann", DeviceKind.PHONE); val bob = newDevice("Bob", DeviceKind.PHONE)
        val space = admin.api.createSpace("Crew")
        join(admin, space.id, ann); join(admin, space.id, bob)
        return Crew(admin, ann, bob, space.id)
    }

    private suspend fun assertError(kind: RelayErrorKind, code: String, block: suspend () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        assertIs<RelayException>(e, "expected RelayException $code")
        assertEquals(kind, e.kind); assertEquals(code, e.message)
    }

    @Test fun createListRenameDeleteChat() = clientTest {
        val c = crew()
        val chat = c.ann.api.createChat(c.spaceId, "  General ")
        assertEquals("General", chat.name); assertEquals(c.ann.id, chat.ownerId); assertTrue(chat.member); assertEquals("Crew", chat.spaceName)
        assertEquals(listOf(chat.id), c.ann.api.chats(c.spaceId).map { it.id })
        assertTrue(c.bob.api.chats(c.spaceId).isEmpty()) // only members (and the admin) see a chat
        assertEquals(listOf(chat.id), c.admin.api.chats(c.spaceId).map { it.id })
        assertFalse(c.admin.api.chats(c.spaceId).single().member)
        assertEquals("Lobby", c.ann.api.renameChat(chat.id, "Lobby").name)
        assertEquals("Lobby2", c.admin.api.renameChat(chat.id, "Lobby2").name)
        c.ann.api.deleteChat(chat.id)
        assertTrue(c.ann.api.chats(c.spaceId).isEmpty())
    }

    @Test fun chatErrors() = clientTest {
        val c = crew()
        assertError(RelayErrorKind.INVALID, "invalid_name") { c.ann.api.createChat(c.spaceId, " ") }
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { c.ann.api.createChat("nope", "X") }
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { c.ann.api.chats("nope") }
        val chat = c.ann.api.createChat(c.spaceId, "General")
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { c.bob.api.renameChat(chat.id, "X") } // existence never leaks
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { c.bob.api.syncChat(chat.id, 0) }
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { c.ann.api.deleteChat("nope") }
        assertError(RelayErrorKind.INVALID, "invalid_name") { c.ann.api.renameChat(chat.id, "") }
    }

    @Test fun inviteApproveJoinFlow() = clientTest {
        val c = crew()
        val chat = c.ann.api.createChat(c.spaceId, "General")
        // The owner invites: approved at once, the invitee sees it.
        val direct = c.ann.api.inviteToChat(chat.id, c.bob.id)
        assertEquals(InviteState.APPROVED, direct.state); assertEquals(c.bob.id, direct.invitee.deviceId); assertEquals("General", direct.chatName)
        assertEquals(listOf(direct.id), c.bob.api.chatInvites().map { it.id })
        assertTrue(c.ann.api.chatInvites().isEmpty())
        c.bob.api.declineInvite(direct.id)
        assertTrue(c.bob.api.chatInvites().isEmpty())
        // A re-invite after a decline works; Bob joins.
        val again = c.ann.api.inviteToChat(chat.id, c.bob.id)
        val joined = c.bob.api.joinInvite(again.id)
        assertEquals(chat.id, joined.id); assertTrue(joined.member)
        assertEquals(setOf(c.ann.id, c.bob.id), c.ann.api.syncChat(chat.id, 0).members.map { it.deviceId }.toSet())
        // A member's invite waits for the owner: Bob invites the admin's... Admin is in the space, so invite Admin.
        val pending = c.bob.api.inviteToChat(chat.id, c.admin.id)
        assertEquals(InviteState.PENDING_APPROVAL, pending.state)
        assertEquals(listOf(pending.id), c.ann.api.chatInvites().map { it.id })
        c.ann.api.denyInvite(pending.id)
        assertTrue(c.ann.api.chatInvites().isEmpty())
        val second = c.bob.api.inviteToChat(chat.id, c.admin.id)
        c.ann.api.approveInvite(second.id)
        assertEquals(listOf(second.id), c.admin.api.chatInvites().map { it.id })
    }

    @Test fun inviteErrors() = clientTest {
        val c = crew()
        val outsider = newDevice("Out", DeviceKind.PHONE)
        val chat = c.ann.api.createChat(c.spaceId, "General")
        assertError(RelayErrorKind.INVALID, "not_in_space") { c.ann.api.inviteToChat(chat.id, outsider.id) }
        assertError(RelayErrorKind.ALREADY_SENT, "already_member") { c.ann.api.inviteToChat(chat.id, c.ann.id) }
        assertError(RelayErrorKind.NOT_PAIRED, "not_member") { c.admin.api.inviteToChat(chat.id, c.bob.id) } // the admin sees it but isn't in it
        val invite = c.ann.api.inviteToChat(chat.id, c.bob.id)
        assertError(RelayErrorKind.ALREADY_SENT, "already_invited") { c.ann.api.inviteToChat(chat.id, c.bob.id) }
        assertError(RelayErrorKind.NOT_PAIRED, "not_invitee") { c.ann.api.joinInvite(invite.id) }
        assertError(RelayErrorKind.NOT_PAIRED, "not_invitee") { c.ann.api.declineInvite(invite.id) }
        assertError(RelayErrorKind.NOT_PAIRED, "not_owner") { c.bob.api.approveInvite(invite.id) }
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { outsider.api.joinInvite(invite.id) }
        assertError(RelayErrorKind.ALREADY_SENT, "invite_unavailable") { c.ann.api.approveInvite(invite.id) } // already approved
        c.bob.api.joinInvite(invite.id)
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { c.bob.api.joinInvite(invite.id) } // a used invite is gone
    }

    @Test fun membershipAndOwnership() = clientTest {
        val c = crew()
        val chat = c.ann.api.createChat(c.spaceId, "General")
        c.bob.api.joinInvite(c.ann.api.inviteToChat(chat.id, c.bob.id).id)
        assertError(RelayErrorKind.ALREADY_SENT, "owner_must_transfer") { c.ann.api.removeChatMember(chat.id, c.ann.id) }
        assertError(RelayErrorKind.NOT_PAIRED, "not_admin") { c.ann.api.removeChatMember(chat.id, c.bob.id) }
        assertError(RelayErrorKind.NOT_PAIRED, "not_owner") { c.bob.api.transferChatOwner(chat.id, c.bob.id) }
        assertError(RelayErrorKind.INVALID, "already_owner") { c.ann.api.transferChatOwner(chat.id, c.ann.id) }
        assertError(RelayErrorKind.INVALID, "not_member") { c.ann.api.transferChatOwner(chat.id, c.admin.id) }
        assertEquals(c.bob.id, c.ann.api.transferChatOwner(chat.id, c.bob.id).ownerId)
        c.ann.api.removeChatMember(chat.id, c.ann.id) // leave
        assertTrue(c.ann.api.chats(c.spaceId).isEmpty())
        // The admin joins directly, then removes Bob (the owner), and the chat remains the admin's.
        assertError(RelayErrorKind.NOT_PAIRED, "not_admin") { c.bob.api.adminJoinChat(chat.id) }
        assertTrue(c.admin.api.adminJoinChat(chat.id).member)
        assertError(RelayErrorKind.ALREADY_SENT, "already_member") { c.admin.api.adminJoinChat(chat.id) }
        c.admin.api.removeChatMember(chat.id, c.bob.id)
        assertEquals(c.admin.id, c.admin.api.chats(c.spaceId).single().ownerId)
        val events = c.admin.api.syncChat(chat.id, 0).messages.mapNotNull { it.event }
        assertTrue(events.any { it is ChatEvent.MemberRemoved && it.byAdmin })
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { c.bob.api.syncChat(chat.id, 0) }
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { c.admin.api.removeChatMember(chat.id, c.bob.id) }
    }

    @Test fun messagesSyncEditDeleteRead() = clientTest {
        val c = crew()
        val chat = c.ann.api.createChat(c.spaceId, "General")
        c.bob.api.joinInvite(c.ann.api.inviteToChat(chat.id, c.bob.id).id)
        val first = c.ann.api.postMessage(chat.id, "hello")
        assertEquals("hello", first.text); assertEquals(c.ann.id, first.author.deviceId)
        val sync = c.bob.api.syncChat(chat.id, 0)
        assertEquals(chat.id, sync.chatId); assertEquals(c.ann.id, sync.ownerId); assertFalse(sync.hasMore)
        assertEquals(sync.messages.maxOf { it.rev }, sync.rev)
        assertEquals(listOf("hello"), sync.messages.filter { it.event == null }.map { it.text })
        assertTrue(c.bob.api.syncChat(chat.id, sync.rev).messages.isEmpty())
        val edited = c.ann.api.editMessage(chat.id, first.id, "hello!")
        assertEquals("hello!", edited.text); assertTrue(edited.editedAtEpochMs != null); assertTrue(edited.rev > first.rev)
        c.ann.api.deleteMessage(chat.id, first.id)
        val after = c.bob.api.syncChat(chat.id, sync.rev).messages.single()
        assertTrue(after.deleted); assertNull(after.text)
        c.bob.api.markChatRead(chat.id, after.rev)
        assertEquals(after.rev, c.bob.api.chats(c.spaceId).single().readRev)
    }

    @Test fun messageErrors() = clientTest {
        val c = crew()
        val chat = c.ann.api.createChat(c.spaceId, "General")
        c.bob.api.joinInvite(c.ann.api.inviteToChat(chat.id, c.bob.id).id)
        assertError(RelayErrorKind.INVALID, "empty") { c.ann.api.postMessage(chat.id, "  ") }
        assertError(RelayErrorKind.INVALID, "too_long") { c.ann.api.postMessage(chat.id, "x".repeat(4001)) }
        val emoji = "😀"
        c.ann.api.postMessage(chat.id, emoji.repeat(4000)) // 4000 code points, 8000 chars
        assertError(RelayErrorKind.INVALID, "too_long") { c.ann.api.postMessage(chat.id, emoji.repeat(4001)) }
        val m = c.ann.api.postMessage(chat.id, "mine")
        assertError(RelayErrorKind.NOT_PAIRED, "not_author") { c.bob.api.editMessage(chat.id, m.id, "x") }
        assertError(RelayErrorKind.NOT_PAIRED, "not_author") { c.bob.api.deleteMessage(chat.id, m.id) }
        assertError(RelayErrorKind.INVALID, "empty") { c.ann.api.editMessage(chat.id, m.id, "") }
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { c.ann.api.editMessage(chat.id, "nope", "x") }
        assertError(RelayErrorKind.NOT_PAIRED, "not_member") { c.admin.api.postMessage(chat.id, "hi") }
        assertError(RelayErrorKind.NOT_PAIRED, "not_member") { c.admin.api.markChatRead(chat.id, 1) }
        c.admin.api.deleteMessage(chat.id, m.id) // the space admin moderates
        assertTrue(c.admin.api.syncChat(chat.id, 0).messages.single { it.id == m.id }.deletedByAdmin)
        assertError(RelayErrorKind.ALREADY_SENT, "deleted") { c.ann.api.editMessage(chat.id, m.id, "x") }
    }

    @Test fun messageRateLimitMapsTo429() = clientTest {
        val c = crew()
        val chat = c.ann.api.createChat(c.spaceId, "General")
        var limited: RelayException? = null
        repeat(70) {
            if (limited == null) try { c.ann.api.postMessage(chat.id, "m$it") } catch (e: RelayException) { limited = e }
        }
        assertEquals(RelayErrorKind.RATE_LIMITED, limited?.kind)
    }

    @Test fun syncPagesAt200() = clientTest {
        val c = crew()
        val chat = c.ann.api.createChat(c.spaceId, "General")
        repeat(205) { deps.store.postMessage(chat.id, c.ann.id, "m$it", 1L + it) }
        val page = c.ann.api.syncChat(chat.id, 0)
        assertEquals(200, page.messages.size); assertTrue(page.hasMore)
        val rest = c.ann.api.syncChat(chat.id, page.messages.maxOf { it.rev })
        assertEquals(5, rest.messages.size); assertFalse(rest.hasMore)
    }

    @Test fun attachmentPostDownloadAndDelete() = clientTest {
        val c = crew()
        val chat = c.ann.api.createChat(c.spaceId, "General")
        c.bob.api.joinInvite(c.ann.api.inviteToChat(chat.id, c.bob.id).id)
        val bytes = ByteArray(3000) { (it % 251).toByte() }
        val ref = uploadResumable(c.ann.api, LocalUpload("mem", "doc.pdf", "application/pdf", bytes.size.toLong()), { off -> bytes.inputStream().apply { skip(off) } }, {})
        val posted = c.ann.api.postMessage(chat.id, null, attachments = listOf(Attachment(ref.id, AttachmentKind.FILE, "doc.pdf", 1, "x/y")))
        assertNull(posted.text)
        val att = posted.attachments.single()
        assertEquals(3000L to "application/pdf", att.sizeBytes to att.mime) // the relay's own values
        assertError(RelayErrorKind.INVALID, "invalid_files") { c.ann.api.postMessage(chat.id, "again", attachments = listOf(att)) } // used once
        val target = kotlin.io.path.createTempFile("chat-dl").toFile()
        c.bob.api.downloadChatFile(att.fileId, target)
        assertEquals(bytes.toList(), target.readBytes().toList())
        c.ann.api.deleteMessage(chat.id, posted.id)
        assertError(RelayErrorKind.NOT_FOUND, "not_found") { c.bob.api.downloadChatFile(att.fileId, target) }
        assertFalse(target.exists())
    }
}
