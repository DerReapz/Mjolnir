package app.mjolnir.server

import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatKind
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.InviteState
import app.mjolnir.protocol.Limits
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatStoreTest {
    private val store = Store("jdbc:sqlite::memory:")
    private var n = 0
    private fun device(name: String = "d${n++}") = store.createDevice(name, DeviceKind.PC, "hash-$name-${n++}", 1000L)

    /** A space with admin [admin] and the given extra members, joined in order at t=20, 30, ... */
    private fun space(admin: DeviceRow, vararg others: DeviceRow): SpaceRow {
        val s = store.createSpace(admin.id, "Home", 10)
        others.forEachIndexed { i, d -> store.addMember(s.id, d.id, 20L + i * 10) }
        return s
    }

    @Test fun createAddsOwnerAsMember() {
        val a = device(); val b = device()
        val s = space(a, b)
        val c = store.createChat(s.id, b.id, "Plans", 100)!!
        assertEquals(b.id, c.ownerId); assertEquals(0L, c.rev)
        assertEquals("Plans", store.chat(c.id)?.name)
        assertTrue(store.isChatMember(c.id, b.id))
        assertFalse(store.isChatMember(c.id, a.id))
        assertEquals(listOf(b.id), store.chatMembers(c.id).map { it.id })
        fun privateOf(d: String, admin: Boolean) = store.chatsOf(s.id, d, admin).filter { it.kind == ChatKind.PRIVATE }.map { it.id }
        assertEquals(listOf(c.id), privateOf(b.id, false))
        assertTrue(privateOf(a.id, false).isEmpty())
        assertEquals(listOf(c.id), privateOf(a.id, true)) // admin sees all
        assertTrue(store.renameChat(c.id, "Trips", b.id, 150)); assertEquals("Trips", store.chat(c.id)?.name)
    }

    @Test fun openChatIncludesEverySpaceMember() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val chat = store.createChat(s.id, b.id, "games", 100, ChatKind.OPEN)!!
        assertEquals(ChatKind.OPEN, chat.kind)
        assertEquals(ChatKind.OPEN, store.chat(chat.id)?.kind)
        assertEquals(setOf(a.id, b.id, c.id), store.chatMembers(chat.id).map { it.id }.toSet())
    }

    @Test fun joiningASpaceJoinsItsOpenChats() {
        val a = device(); val b = device(); val d = device()
        val s = space(a, b)
        val games = store.createChat(s.id, b.id, "games", 100, ChatKind.OPEN)!!
        val priv = store.createChat(s.id, b.id, "secret", 100)!!
        val joined = store.addMember(s.id, d.id, 200)
        val open = store.openChatsIn(s.id).map { it.id }
        assertEquals(2, open.size)
        assertEquals(open.toSet(), joined.toSet())
        assertTrue(games.id in joined)
        open.forEach { assertTrue(store.isChatMember(it, d.id)) }
        assertFalse(store.isChatMember(priv.id, d.id))
        assertTrue(store.addMember(s.id, d.id, 210).isEmpty())
    }

    @Test fun leavingASpaceLeavesItsOpenChats() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val games = store.createChat(s.id, b.id, "games", 100, ChatKind.OPEN)!!
        val res = store.removeMember(s.id, c.id, c.id, 300)
        assertFalse(store.isChatMember(games.id, c.id))
        assertTrue(games.id in res.chats.changed.keys)
        store.openChatsIn(s.id).forEach { assertFalse(store.isChatMember(it.id, c.id)) }
    }

    @Test fun rejoiningRestoresOpenChats() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val games = store.createChat(s.id, b.id, "games", 100, ChatKind.OPEN)!!
        store.postMessage(games.id, b.id, "hello", 110)
        store.removeMember(s.id, c.id, c.id, 300)
        store.addMember(s.id, c.id, 400)
        assertTrue(store.isChatMember(games.id, c.id))
        assertEquals(1, store.chatMembers(games.id).count { it.id == c.id })
        assertTrue(store.syncPage(games.id, 0).rows.any { it.text == "hello" })
    }

    @Test fun creatorLeavingKeepsTheChannel() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val games = store.createChat(s.id, b.id, "games", 100, ChatKind.OPEN)!!
        store.removeMember(s.id, b.id, b.id, 300)
        val after = store.chat(games.id)!!
        assertEquals(store.chatMembers(games.id).first().id, after.ownerId)
        assertTrue(after.ownerId != b.id)
    }

    @Test fun openChatTenureFollowsTheSpace() {
        // Ids sort against join order, so a tie on joined_at would pick the wrong next owner.
        val a = store.createDevice("a", DeviceKind.PC, "hash-za", 1000L, id = "z-a")
        val b = store.createDevice("b", DeviceKind.PC, "hash-yb", 1000L, id = "y-b")
        val c = store.createDevice("c", DeviceKind.PC, "hash-xc", 1000L, id = "x-c")
        val s = space(a, b, c) // joined at 10, 20, 30
        val games = store.createChat(s.id, c.id, "games", 100, ChatKind.OPEN)!!
        assertEquals(listOf(a.id, b.id, c.id), store.chatMembers(games.id).map { it.id })
        store.removeMember(s.id, c.id, c.id, 300)
        assertEquals(a.id, store.chat(games.id)!!.ownerId)
        // A later joiner of the space comes after them, at its join time.
        val d = store.createDevice("d", DeviceKind.PC, "hash-wd", 1000L, id = "w-d")
        store.addMember(s.id, d.id, 400)
        assertEquals(listOf(a.id, b.id, d.id), store.chatMembers(games.id).map { it.id })
    }

    @Test fun muteIsPerMember() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val games = store.createChat(s.id, b.id, "games", 100, ChatKind.OPEN)!!
        assertTrue(store.setMuted(games.id, b.id, true))
        assertTrue(store.isMuted(games.id, b.id))
        assertFalse(store.isMuted(games.id, c.id))
        val outsider = device()
        assertFalse(store.setMuted(games.id, outsider.id, true))
        assertTrue(store.setMuted(games.id, b.id, false))
        assertFalse(store.isMuted(games.id, b.id))
    }

    @Test fun postEditDeleteBumpRev() {
        val a = device(); val b = device()
        val s = space(a, b)
        val c = store.createChat(s.id, a.id, "Plans", 100)!!
        val m1 = store.postMessage(c.id, a.id, "hi", 101)!!
        val m2 = store.postMessage(c.id, a.id, "there", 102)!!
        assertEquals(listOf(1L, 2L), listOf(m1.rev, m2.rev))
        assertEquals(2L, store.chat(c.id)!!.rev)
        assertTrue(store.editMessage(c.id, m1.id, a.id, "hello", 103))
        assertEquals(3L, store.chat(c.id)!!.rev)
        assertFalse(store.editMessage(c.id, m1.id, b.id, "hijack", 104)) // author only
        assertTrue(store.deleteMessage(c.id, m2.id, a.id, false))
        assertFalse(store.deleteMessage(c.id, m2.id, a.id, false)) // already deleted
        assertFalse(store.editMessage(c.id, m2.id, a.id, "zombie", 105))
        assertEquals(4L, store.chat(c.id)!!.rev)
        val (msgs, more) = store.sync(c.id, 0)
        assertFalse(more)
        val edited = msgs.single { it.id == m1.id }
        assertEquals("hello", edited.text); assertEquals(3L, edited.rev); assertEquals(103L, edited.editedAtMs)
        val gone = msgs.single { it.id == m2.id }
        assertTrue(gone.deleted); assertNull(gone.text); assertFalse(gone.deletedByAdmin); assertEquals(4L, gone.rev)
    }

    @Test fun adminDeleteIsMarkedOnlyForOthersMessages() {
        val a = device(); val b = device()
        val s = space(a, b)
        val c = store.createChat(s.id, b.id, "Plans", 100)!!
        store.addChatMember(c.id, a.id, 101)
        val theirs = store.postMessage(c.id, b.id, "x", 102)!!
        val mine = store.postMessage(c.id, a.id, "y", 103)!!
        assertFalse(store.deleteMessage(c.id, theirs.id, a.id, false)) // a plain member can't delete another's
        assertTrue(store.deleteMessage(c.id, theirs.id, a.id, true))
        assertTrue(store.deleteMessage(c.id, mine.id, a.id, true))
        val msgs = store.sync(c.id, 0).first
        assertTrue(msgs.single { it.id == theirs.id }.deletedByAdmin)
        assertFalse(msgs.single { it.id == mine.id }.deletedByAdmin)
    }

    @Test fun revStrictlyIncreasesUnderConcurrentWrites() {
        val a = device()
        val s = space(a)
        val c = store.createChat(s.id, a.id, "Busy", 100)!!
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val postRevs = CopyOnWriteArrayList<Long>()
        val futures = (0 until 8).map {
            pool.submit {
                go.await()
                var last = 0L
                val mine = (0 until 50).map {
                    val m = store.postMessage(c.id, a.id, "m", 1)!!
                    assertTrue(m.rev > last); last = m.rev
                    postRevs += m.rev
                    m
                }
                mine.forEach { m ->
                    assertTrue(store.editMessage(c.id, m.id, a.id, "e", 2))
                    val rev = store.sync(c.id, 0, 1000).first.single { it.id == m.id }.rev
                    assertTrue(rev > last); last = rev
                }
            }
        }
        go.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        assertEquals(400, postRevs.toSet().size)
        assertEquals(800L, store.chat(c.id)!!.rev)
        val finalRevs = store.sync(c.id, 0, 1000).first.map { it.rev }
        assertEquals(400, finalRevs.toSet().size)
        assertEquals(finalRevs.sorted(), finalRevs)
    }

    @Test fun syncPageReadsTheChatRevWithTheRows() {
        val a = device()
        val s = space(a)
        val c = store.createChat(s.id, a.id, "Plans", 100)!!
        val pool = Executors.newFixedThreadPool(2)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val poster = pool.submit { while (!stop.get()) store.postMessage(c.id, a.id, "m", 1) }
        try {
            repeat(300) {
                val page = store.syncPage(c.id, 0, 1_000_000)
                if (!page.hasMore && page.rows.isNotEmpty()) assertEquals(page.rows.maxOf { it.rev }, page.chatRev)
            }
        } finally { stop.set(true); poster.get(); pool.shutdown() }
        val m = store.postMessage(c.id, a.id, "last", 1)!!
        assertEquals(m.rev, store.syncPage(c.id, 0, 1_000_000).chatRev)
        assertEquals(m.rev, store.syncPage(c.id, m.rev).chatRev) // nothing new: the rev is still the chat's
    }

    @Test fun lastChatMessageFollowsCreationNotEdits() {
        val a = device()
        val s = space(a)
        val c = store.createChat(s.id, a.id, "Plans", 100)!!
        val first = store.postMessage(c.id, a.id, "first", 101)!!
        store.postMessage(c.id, a.id, "second", 102)
        store.editMessage(c.id, first.id, a.id, "first, edited", 103)
        assertEquals("second", store.lastChatMessage(c.id)!!.text)
    }

    @Test fun syncReturnsChangedSinceRevIncludingEditsAndDeletes() {
        val a = device()
        val s = space(a)
        val c = store.createChat(s.id, a.id, "Plans", 100)!!
        val m1 = store.postMessage(c.id, a.id, "one", 101)!!
        val m2 = store.postMessage(c.id, a.id, "two", 102)!!
        val m3 = store.postMessage(c.id, a.id, "three", 103)!!
        assertEquals(listOf(m3.id), store.sync(c.id, m2.rev).first.map { it.id })
        assertTrue(store.sync(c.id, 3).first.isEmpty())
        store.editMessage(c.id, m1.id, a.id, "uno", 104)
        store.deleteMessage(c.id, m2.id, a.id, false)
        val (since3, more) = store.sync(c.id, 3)
        assertFalse(more)
        assertEquals(listOf(m1.id, m2.id), since3.map { it.id })
        assertEquals("uno", since3[0].text); assertTrue(since3[1].deleted)
    }

    @Test fun syncPagesLargeBacklog() {
        val a = device()
        val s = space(a)
        val c = store.createChat(s.id, a.id, "Big", 100)!!
        repeat(450) { store.postMessage(c.id, a.id, "m$it", 101)!! }
        val p1 = store.sync(c.id, 0, 200)
        val p2 = store.sync(c.id, p1.first.last().rev, 200)
        val p3 = store.sync(c.id, p2.first.last().rev, 200)
        assertEquals(listOf(200, 200, 50), listOf(p1, p2, p3).map { it.first.size })
        assertEquals(listOf(true, true, false), listOf(p1, p2, p3).map { it.second })
        assertEquals(450, (p1.first + p2.first + p3.first).map { it.id }.toSet().size)
        assertEquals(200, store.sync(c.id, 0).first.size) // default limit
    }

    @Test fun ownerCannotLeave() {
        val a = device(); val b = device()
        val s = space(a, b)
        val c = store.createChat(s.id, a.id, "Plans", 100)!!
        store.addChatMember(c.id, b.id, 101)
        assertEquals(Store.RemoveResult.OWNER_MUST_TRANSFER, store.removeChatMember(c.id, a.id, false, 500))
        assertFalse(store.transferChatOwner(c.id, b.id, a.id, 500)) // not the owner
        assertTrue(store.transferChatOwner(c.id, a.id, b.id, 500))
        assertEquals(b.id, store.chat(c.id)!!.ownerId)
        assertEquals(Store.RemoveResult.REMOVED, store.removeChatMember(c.id, a.id, false, 500))
        assertEquals(Store.RemoveResult.NOT_MEMBER, store.removeChatMember(c.id, a.id, false, 500))
        assertFalse(store.transferChatOwner(c.id, b.id, a.id, 500)) // target left
    }

    @Test fun lastMemberLeavingDeletesChat() {
        val a = device(); val b = device()
        val s = space(a, b)
        store.openChatsIn(s.id).forEach { store.deleteChat(it.id) } // #general would otherwise survive with its rows
        val c = store.createChat(s.id, b.id, "Plans", 100)!!
        store.postMessage(c.id, b.id, "hi", 102)!!
        // The owner is always the last one standing, and can't leave through removeChatMember; leaving the space does it.
        assertEquals(Store.RemoveResult.OWNER_MUST_TRANSFER, store.removeChatMember(c.id, b.id, false, 500))
        assertEquals(Store.Removed.REMOVED, store.removeMember(s.id, b.id, b.id, 500).result)
        assertNull(store.chat(c.id))
        assertEquals(0, store.rowCount("chat_messages"))
        assertEquals(0, store.rowCount("chat_members"))
    }

    @Test fun onLeftSpaceTransfersOwnershipToLongestStanding() {
        val a = device(); val b = device(); val c = device(); val d = device()
        val s = space(a, b, c, d)
        val chat = store.createChat(s.id, b.id, "Plans", 100)!!
        store.addChatMember(chat.id, d.id, 110)
        store.addChatMember(chat.id, c.id, 120)
        assertEquals(Store.Removed.REMOVED, store.removeMember(s.id, b.id, a.id, 500).result)
        assertEquals(d.id, store.chat(chat.id)!!.ownerId) // d joined the chat before c
        assertFalse(store.isChatMember(chat.id, b.id))
        assertEquals(listOf(d.id, c.id), store.chatMembers(chat.id).map { it.id })
        // The others are untouched by an unrelated leave
        assertEquals(Store.Removed.REMOVED, store.removeMember(s.id, c.id, c.id, 500).result)
        assertEquals(listOf(d.id), store.chatMembers(chat.id).map { it.id })
        assertEquals(Store.Removed.REMOVED, store.removeMember(s.id, d.id, a.id, 500).result)
        assertNull(store.chat(chat.id)) // last member gone: chat deleted
    }

    @Test fun onlyChatsOfThatSpaceAreAffected() {
        val a = device(); val b = device()
        val s1 = space(a, b); val s2 = store.createSpace(a.id, "Other", 10).also { store.addMember(it.id, b.id, 20) }
        val c1 = store.createChat(s1.id, b.id, "One", 100)!!; store.addChatMember(c1.id, a.id, 101)
        val c2 = store.createChat(s2.id, b.id, "Two", 100)!!; store.addChatMember(c2.id, a.id, 101)
        store.removeMember(s1.id, b.id, b.id, 500).result
        assertEquals(a.id, store.chat(c1.id)!!.ownerId)
        assertTrue(store.isChatMember(c2.id, b.id))
    }

    @Test fun inviteLifecycle() {
        val a = device(); val b = device(); val c = device(); val outsider = device()
        val s = space(a, b, c)
        val chat = store.createChat(s.id, a.id, "Plans", 100)!!
        store.addChatMember(chat.id, b.id, 101)
        // b (not owner) invites c: needs a's approval
        val inv = store.createInvite(chat.id, c.id, b.id, 200)!!
        assertEquals(InviteState.PENDING_APPROVAL, inv.state)
        assertNull(store.createInvite(chat.id, c.id, b.id, 201)) // already invited
        assertNull(store.createInvite(chat.id, b.id, a.id, 201)) // already a member
        assertNull(store.createInvite(chat.id, outsider.id, a.id, 201)) // not in the space
        assertEquals(listOf(inv.id), store.invitesFor(a.id, 202).map { it.id }) // owner owes an approval
        assertTrue(store.invitesFor(c.id, 202).isEmpty()) // invitee sees nothing yet
        assertFalse(store.joinViaInvite(inv.id, c.id, 203)) // no joining before approval
        assertFalse(store.setInviteState(inv.id, InviteState.APPROVED, InviteState.DECLINED, 250)) // wrong from-state
        assertTrue(store.setInviteState(inv.id, InviteState.PENDING_APPROVAL, InviteState.APPROVED, 250))
        assertTrue(store.invitesFor(a.id, 204).isEmpty())
        assertEquals(listOf(inv.id), store.invitesFor(c.id, 204).map { it.id })
        assertFalse(store.joinViaInvite(inv.id, b.id, 205)) // only the invitee
        assertTrue(store.joinViaInvite(inv.id, c.id, 206))
        assertTrue(store.isChatMember(chat.id, c.id))
        assertFalse(store.joinViaInvite(inv.id, c.id, 207)) // consumed
        assertTrue(store.invitesFor(c.id, 207).isEmpty())
        // the owner's own invitation skips approval
        val d = device(); store.addMember(s.id, d.id, 300)
        assertEquals(InviteState.APPROVED, store.createInvite(chat.id, d.id, a.id, 301)!!.state)
    }

    @Test fun deniedAndDeclinedInvitesGrantNothing() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val chat = store.createChat(s.id, a.id, "Plans", 100)!!
        store.addChatMember(chat.id, b.id, 101)
        val denied = store.createInvite(chat.id, c.id, b.id, 200)!!
        assertTrue(store.setInviteState(denied.id, InviteState.PENDING_APPROVAL, InviteState.DENIED, 250))
        assertFalse(store.joinViaInvite(denied.id, c.id, 201))
        assertTrue(store.invitesFor(a.id, 201).isEmpty()); assertTrue(store.invitesFor(c.id, 201).isEmpty())
        assertFalse(store.isChatMember(chat.id, c.id))
        // Re-inviting after a denial is allowed; decline this time.
        val again = store.createInvite(chat.id, c.id, a.id, 300)!!
        assertEquals(InviteState.APPROVED, again.state)
        assertTrue(store.setInviteState(again.id, InviteState.APPROVED, InviteState.DECLINED, 250))
        assertFalse(store.joinViaInvite(again.id, c.id, 301))
        assertTrue(store.invitesFor(c.id, 301).isEmpty())
        assertFalse(store.isChatMember(chat.id, c.id))
    }

    @Test fun inviteExpiresAfter7Days() {
        val a = device(); val b = device()
        val s = space(a, b)
        val chat = store.createChat(s.id, a.id, "Plans", 100)!!
        val inv = store.createInvite(chat.id, b.id, a.id, 1000)!!
        val ttl = Limits.CHAT_INVITE_TTL_MS
        assertEquals(7L * 24 * 3600 * 1000, ttl)
        assertEquals(1, store.invitesFor(b.id, 1000 + ttl - 1).size)
        assertTrue(store.invitesFor(b.id, 1000 + ttl).isEmpty())
        assertFalse(store.joinViaInvite(inv.id, b.id, 1000 + ttl))
        assertFalse(store.isChatMember(chat.id, b.id))
        // an expired invite doesn't block a fresh one
        assertNotNull(store.createInvite(chat.id, b.id, a.id, 1000 + ttl + 5))
    }

    @Test fun inviteDiesWhenInviterLeavesSpace() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val chat = store.createChat(s.id, a.id, "Plans", 100)!!
        store.addChatMember(chat.id, b.id, 101)
        val inv = store.createInvite(chat.id, c.id, b.id, 200)!!
        assertTrue(store.setInviteState(inv.id, InviteState.PENDING_APPROVAL, InviteState.APPROVED, 250))
        store.removeMember(s.id, b.id, b.id, 500).result
        assertNull(store.chatInvite(inv.id))
        assertTrue(store.invitesFor(c.id, 201).isEmpty())
        assertFalse(store.joinViaInvite(inv.id, c.id, 202))
        assertEquals(0, store.rowCount("chat_invites"))
        // invitee leaving the space kills theirs too
        val inv2 = store.createInvite(chat.id, c.id, a.id, 300)!!
        store.removeMember(s.id, c.id, c.id, 500).result
        assertNull(store.chatInvite(inv2.id))
    }

    @Test fun invitesForRechecksSpaceMembership() {
        // Another handle deletes the space membership without going through removeMember, so the invite row survives.
        val file = kotlin.io.path.createTempDirectory().resolve("r.db").toString()
        val st = Store("jdbc:sqlite:$file")
        val a = st.createDevice("a", DeviceKind.PC, "ha", 1); val b = st.createDevice("b", DeviceKind.PC, "hb", 1)
        val sp = st.createSpace(a.id, "Home", 10); st.addMember(sp.id, b.id, 20)
        val chat = st.createChat(sp.id, a.id, "Plans", 100)!!
        val inv = st.createInvite(chat.id, b.id, a.id, 200)!!
        assertEquals(listOf(inv.id), st.invitesFor(b.id, 201).map { it.id })
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM space_members WHERE device_id='${b.id}'") }
        }
        assertNotNull(st.chatInvite(inv.id))
        assertTrue(st.invitesFor(b.id, 202).isEmpty())
        assertTrue(st.invitesFor(a.id, 202).isEmpty())
        assertFalse(st.joinViaInvite(inv.id, b.id, 203))
    }

    @Test fun deleteSpaceDeletesChats() {
        val a = device(); val b = device()
        val s = space(a, b)
        val c1 = store.createChat(s.id, a.id, "One", 100)!!; store.addChatMember(c1.id, b.id, 101)
        val c2 = store.createChat(s.id, b.id, "Two", 100)!!
        store.postMessage(c1.id, a.id, "hi", 102)!!
        store.createInvite(c2.id, a.id, b.id, 103)
        assertNotNull(store.deleteSpace(s.id, a.id))
        assertNull(store.chat(c1.id)); assertNull(store.chat(c2.id))
        for (t in listOf("chats", "chat_members", "chat_invites", "chat_messages")) assertEquals(0, store.rowCount(t), t)
    }

    @Test fun markReadOnlyMovesForwardAndStaysWithinRev() {
        val a = device()
        val s = space(a)
        val c = store.createChat(s.id, a.id, "Plans", 100)!!
        repeat(3) { store.postMessage(c.id, a.id, "m", 101)!! }
        store.markRead(c.id, a.id, 2, 0); assertEquals(2L, store.readRev(c.id, a.id))
        store.markRead(c.id, a.id, 1, 0); assertEquals(2L, store.readRev(c.id, a.id))
        store.markRead(c.id, a.id, 99, 0); assertEquals(3L, store.readRev(c.id, a.id))
    }

    @Test fun nonSpaceMembersCannotBeChatMembers() {
        val a = device(); val outsider = device()
        val s = space(a)
        val c = store.createChat(s.id, a.id, "Plans", 100)!!
        assertFalse(store.addChatMember(c.id, outsider.id, 101))
        assertFalse(store.isChatMember(c.id, outsider.id))
    }

    private fun events(chatId: String) = store.sync(chatId, 0, 1000).first.filter { it.event != null }

    @Test fun adminRemovalRecordedAsEvent() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val chat = store.createChat(s.id, b.id, "Plans", 100)!!
        store.addChatMember(chat.id, c.id, 101)
        store.createInvite(chat.id, a.id, c.id, 102) // c invited the admin; removing c drops it
        assertEquals(Store.RemoveResult.REMOVED, store.removeChatMember(chat.id, c.id, true, 110, actorId = a.id))
        val removed = events(chat.id).last()
        assertEquals(ChatEvent.MemberRemoved(c.id, true), removed.event)
        assertEquals(a.id, removed.authorId); assertNull(removed.text); assertEquals(110L, removed.sentAtMs)
        assertEquals(0, store.rowCount("chat_invites"))
        assertEquals(store.chat(chat.id)!!.rev, removed.rev)
        // A voluntary leave is MemberLeft by the leaver.
        store.addChatMember(chat.id, c.id, 120)
        assertEquals(Store.RemoveResult.REMOVED, store.removeChatMember(chat.id, c.id, false, 130))
        assertEquals(ChatEvent.MemberLeft(c.id), events(chat.id).last().event)
        // Events can't be edited or deleted as messages.
        assertFalse(store.deleteMessage(chat.id, removed.id, a.id, true))
        assertFalse(store.editMessage(chat.id, removed.id, a.id, "x", 140))
    }

    @Test fun joinAndOwnerChangeRecorded() {
        val a = device(); val b = device()
        val s = space(a, b)
        val chat = store.createChat(s.id, a.id, "Plans", 100)!!
        val inv = store.createInvite(chat.id, b.id, a.id, 110)!!
        assertTrue(store.joinViaInvite(inv.id, b.id, 120))
        assertTrue(store.transferChatOwner(chat.id, a.id, b.id, 130))
        assertTrue(store.renameChat(chat.id, "Trips", b.id, 140))
        val evs = events(chat.id)
        assertEquals(listOf(ChatEvent.MemberJoined(b.id), ChatEvent.OwnerChanged(b.id), ChatEvent.Renamed("Trips")), evs.map { it.event })
        assertEquals(listOf(b.id, a.id, b.id), evs.map { it.authorId })
        assertTrue(evs.all { it.text == null && !it.deleted })
    }

    @Test fun memberAndOwnerChangesBumpRev() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val chat = store.createChat(s.id, a.id, "Plans", 100)!!
        var rev = 0L
        fun bumped() { val r = store.chat(chat.id)!!.rev; assertTrue(r > rev); rev = r }
        store.addChatMember(chat.id, b.id, 101); bumped()
        store.addChatMember(chat.id, c.id, 102); bumped()
        assertTrue(store.transferChatOwner(chat.id, a.id, b.id, 103)); bumped()
        assertTrue(store.renameChat(chat.id, "X", b.id, 104)); bumped()
        store.removeChatMember(chat.id, c.id, false, 105); bumped()
        // a failed change doesn't bump
        assertFalse(store.transferChatOwner(chat.id, a.id, b.id, 106)); assertEquals(rev, store.chat(chat.id)!!.rev)
        assertFalse(store.addChatMember(chat.id, b.id, 107)); assertEquals(rev, store.chat(chat.id)!!.rev)
    }

    @Test fun editOrDeleteWithWrongChatIdFails() {
        val a = device()
        val s = space(a)
        val c1 = store.createChat(s.id, a.id, "One", 100)!!; val c2 = store.createChat(s.id, a.id, "Two", 100)!!
        val m = store.postMessage(c1.id, a.id, "hi", 101)!!
        assertFalse(store.editMessage(c2.id, m.id, a.id, "x", 102))
        assertFalse(store.deleteMessage(c2.id, m.id, a.id, true))
        assertEquals(1L, store.chat(c1.id)!!.rev); assertEquals(0L, store.chat(c2.id)!!.rev)
        assertTrue(store.editMessage(c1.id, m.id, a.id, "x", 103))
        assertTrue(store.deleteMessage(c1.id, m.id, a.id, false))
    }

    @Test fun leftSpaceResultNamesWhoToNotify() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val keep = store.createChat(s.id, a.id, "Keep", 100)!!; store.addChatMember(keep.id, b.id, 101); store.addChatMember(keep.id, c.id, 102)
        val gone = store.createChat(s.id, b.id, "Gone", 100)!!
        val other = store.createChat(s.id, a.id, "Other", 100)!!
        val r = store.removeMember(s.id, b.id, b.id, 200)
        assertEquals(Store.Removed.REMOVED, r.result)
        val privateIds = setOf(keep.id, gone.id, other.id)
        assertEquals(setOf(keep.id), r.chats.changed.keys.filter { it in privateIds }.toSet()) // #general changes too
        assertEquals(setOf(a.id, b.id, c.id), r.chats.changed.getValue(keep.id).toSet()) // remaining members plus the leaver
        assertEquals(mapOf(gone.id to listOf(b.id)), r.chats.deleted)
        assertFalse(r.chats.changed.containsKey(other.id))
        assertEquals(ChatEvent.MemberLeft(b.id), events(keep.id).last().event)
        // No-op removals return an empty result.
        assertEquals(Store.Removed.NOT_MEMBER, store.removeMember(s.id, b.id, b.id, 201).result)
        assertTrue(store.removeMember(s.id, b.id, b.id, 201).chats.changed.isEmpty())
        // deleteSpace reports the chats it removed with their members
        val d = store.deleteSpace(s.id, a.id)!!
        assertEquals(setOf(keep.id, other.id), d.chats.keys.filter { it in privateIds }.toSet())
        assertEquals(setOf(a.id, c.id), d.chats.getValue(keep.id).toSet())
        assertEquals(setOf(a.id, c.id), d.memberIds.toSet())
    }

    @Test fun adminRemovingOwnerPassesOwnershipOrDeletesChat() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val chat = store.createChat(s.id, b.id, "Plans", 100)!!
        store.addChatMember(chat.id, c.id, 110)
        store.addChatMember(chat.id, a.id, 120)
        assertEquals(Store.RemoveResult.OWNER_MUST_TRANSFER, store.removeChatMember(chat.id, b.id, false, 130))
        assertEquals(Store.RemoveResult.REMOVED, store.removeChatMember(chat.id, b.id, true, 140, actorId = a.id))
        assertEquals(c.id, store.chat(chat.id)!!.ownerId) // longest-standing remaining
        assertEquals(listOf(ChatEvent.MemberRemoved(b.id, true), ChatEvent.OwnerChanged(c.id)), events(chat.id).takeLast(2).map { it.event })
        val solo = store.createChat(s.id, b.id, "Solo", 100)!!
        assertEquals(Store.RemoveResult.LAST_MEMBER_DELETED, store.removeChatMember(solo.id, b.id, true, 150, actorId = a.id))
        assertNull(store.chat(solo.id))
    }

    @Test fun postByNonMemberReturnsNull() {
        val a = device(); val b = device()
        val s = space(a, b)
        val c = store.createChat(s.id, a.id, "Plans", 100)!!
        assertNull(store.postMessage(c.id, b.id, "hi", 101))
        assertNull(store.postMessage("nope", a.id, "hi", 101))
        assertEquals(0L, store.chat(c.id)!!.rev)
    }

    @Test fun inviteStateTransitionsAreRestrictedAndExpire() {
        val a = device(); val b = device(); val c = device()
        val s = space(a, b, c)
        val chat = store.createChat(s.id, a.id, "Plans", 100)!!
        store.addChatMember(chat.id, b.id, 101)
        val inv = store.createInvite(chat.id, c.id, b.id, 1000)!!
        assertFalse(store.setInviteState(inv.id, InviteState.PENDING_APPROVAL, InviteState.DECLINED, 1001))
        assertFalse(store.setInviteState(inv.id, InviteState.PENDING_APPROVAL, InviteState.PENDING_APPROVAL, 1001))
        assertFalse(store.setInviteState(inv.id, InviteState.PENDING_APPROVAL, InviteState.APPROVED, 1000 + Limits.CHAT_INVITE_TTL_MS))
        assertTrue(store.setInviteState(inv.id, InviteState.PENDING_APPROVAL, InviteState.APPROVED, 1001))
        assertFalse(store.setInviteState(inv.id, InviteState.APPROVED, InviteState.DENIED, 1002))
        assertFalse(store.setInviteState(inv.id, InviteState.APPROVED, InviteState.DECLINED, 1000 + Limits.CHAT_INVITE_TTL_MS))
        assertTrue(store.setInviteState(inv.id, InviteState.APPROVED, InviteState.DECLINED, 1002))
        assertFalse(store.setInviteState(inv.id, InviteState.DECLINED, InviteState.APPROVED, 1003))
    }

    @Test fun createChatRequiresSpaceMembership() {
        val a = device(); val outsider = device()
        val s = space(a)
        assertNull(store.createChat(s.id, outsider.id, "Nope", 100))
        assertNull(store.createChat("no-space", a.id, "Nope", 100))
        assertEquals(1, store.rowCount("chats")) // only #general
    }
}
