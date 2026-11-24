package app.mjolnir.client

import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatKind
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.Receipt
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRulesTest {
    @Test fun capsAtCodePointsNeverSplittingASurrogatePair() {
        val capped = capCodePoints("a".repeat(3999) + "😀😀", 4000)
        assertEquals(4000, capped.codePointCount(0, capped.length))
        assertEquals("a".repeat(3999) + "😀", capped)
        assertEquals("short", capCodePoints("short", 4000))
    }

    @Test fun inviteCandidatesAreSpaceMembersNotInTheChat() {
        val me = Peer("me", "Me", DeviceKind.PC); val fred = Peer("fred", "Fred", DeviceKind.PHONE); val zed = Peer("zed", "Zed", DeviceKind.PC)
        val space = Space("s1", "Friends", "admin", listOf(Peer("admin", "Admin", DeviceKind.PC), me, fred, zed))
        assertEquals(listOf("admin", "zed"), inviteCandidates(space, listOf(me, fred), "me").map { it.deviceId })
    }

    @Test fun systemLinesAndErrorCopy() {
        val names = mapOf("sam" to "Sam", "ann" to "Ann")
        val nameOf = { id: String -> names[id] ?: "Someone" }
        assertEquals("Sam joined", Messages.systemLine(ChatEvent.MemberJoined("sam"), nameOf))
        assertEquals("Sam left", Messages.systemLine(ChatEvent.MemberLeft("sam"), nameOf))
        assertEquals("Ann · Removed by the space admin", Messages.systemLine(ChatEvent.MemberRemoved("ann", byAdmin = true), nameOf))
        assertEquals("Ann was removed", Messages.systemLine(ChatEvent.MemberRemoved("ann", byAdmin = false), nameOf))
        assertEquals("Someone is now the owner", Messages.systemLine(ChatEvent.OwnerChanged("x"), nameOf))
        assertEquals("Renamed to Trip", Messages.systemLine(ChatEvent.Renamed("Trip"), nameOf))
        assertEquals("Make someone else the owner first.", Messages.chatError(RelayException(RelayErrorKind.ALREADY_SENT, "owner_must_transfer")))
        assertEquals("Already invited.", Messages.chatError(RelayException(RelayErrorKind.ALREADY_SENT, "already_invited")))
        assertEquals("Already in this chat.", Messages.chatError(RelayException(RelayErrorKind.ALREADY_SENT, "already_member")))
        assertEquals("That chat is no longer there.", Messages.chatError(RelayException(RelayErrorKind.NOT_FOUND, "not_found")))
        assertEquals("Can't reach the relay. Check your connection.", Messages.chatError(RelayException(RelayErrorKind.NETWORK, "x")))
    }

    private val me = Peer("me", "Me", DeviceKind.PC)
    private val ann = Peer("ann", "Ann", DeviceKind.PHONE)
    private val bo = Peer("bo", "Bo", DeviceKind.PC)
    private val cy = Peer("cy", "Cy", DeviceKind.PC)

    @Test fun mentionQueryNeedsAnAtAtAWordStart() {
        assertEquals("", mentionQuery("@")); assertEquals("an", mentionQuery("hi @an")); assertEquals("", mentionQuery("hi @"))
        assertNull(mentionQuery("mail me@an")); assertNull(mentionQuery("hi @an ")); assertNull(mentionQuery("hi"))
        assertEquals("a", mentionQuery("@x @a"))
    }

    @Test fun insertMentionReplacesTheQuery() {
        assertEquals("hi @Ann ", insertMention("hi @an", "Ann"))
        assertEquals("@Ann ", insertMention("@", "Ann"))
    }

    @Test fun outgoingMentionsUseTheCanonicalNameAndDropEditedOnes() {
        val picked = listOf(ann to "Annie", bo to "Bo")
        assertEquals("hi @Ann and @Bo" to listOf("ann", "bo"), outgoingMentions("hi @Annie and @Bo", picked))
        assertEquals("@Bo @Ann" to listOf("bo", "ann"), outgoingMentions("@Bo @Annie", picked)) // in order of appearance
        assertEquals("hi @Ann and Bo" to listOf("ann"), outgoingMentions("hi @Annie and Bo", picked)) // "@Bo" was edited away
        assertEquals("plain" to emptyList(), outgoingMentions("plain", picked))
    }

    @Test fun mentionsMatchWholeNamesOnly() {
        val annabel = Peer("annabel", "Annabel", DeviceKind.PC)
        val picked = listOf(ann to "Ann", annabel to "Annabel")
        assertEquals("@Annabel hi" to listOf("annabel"), outgoingMentions("@Annabel hi", picked))
        assertEquals("@Ann, @Annabel!" to listOf("ann", "annabel"), outgoingMentions("@Ann, @Annabel!", picked))
        val shown = mentionSpans("@Annabel and @Ann", listOf("ann", "annabel"), listOf(ann, annabel)).spans
        assertEquals(listOf(0..7, 13..16), shown)
    }

    @Test fun receiptLineForUsesShownNames() {
        val m = msg("a", me, 5)
        val receipts = listOf(Receipt("ann", 5, 5), Receipt("bo", 5, 4))
        assertEquals("Read by Annie · Seen by Bo", receiptLineFor(m, receipts, listOf(me, ann, bo, cy)) { if (it.deviceId == "ann") "Annie" else it.name })
        assertEquals("", receiptLineFor(m, emptyList(), listOf(me, ann)))
    }

    @Test fun mentionSpansShowNicknames() {
        val shown = mentionSpans("hi @Ann and @Bo", listOf("ann", "bo"), listOf(me, ann, bo)) { if (it.deviceId == "ann") "Annie" else it.name }
        assertEquals("hi @Annie and @Bo", shown.text)
        assertEquals(listOf("@Annie", "@Bo"), shown.spans.map { shown.text.substring(it) })
        val plain = mentionSpans("hi @Ann", emptyList(), listOf(ann)) { it.name }
        assertEquals("hi @Ann", plain.text); assertEquals(emptyList(), plain.spans)
        assertEquals(emptyList(), mentionSpans("hi @Zed", listOf("zed"), listOf(ann)) { it.name }.spans) // not a member any more
    }

    private fun msg(id: String, author: Peer, rev: Long, deleted: Boolean = false, event: ChatEvent? = null) =
        ChatMessage(id, "c", author, rev, rev, deleted = deleted, text = if (deleted || event != null) null else id, event = event)

    @Test fun latestOwnMessageSkipsEventsAndDeleted() {
        val all = listOf(msg("a", me, 1), msg("b", ann, 2), msg("c", me, 3, deleted = true), msg("d", me, 4, event = ChatEvent.MemberLeft("x")))
        assertEquals("a", latestOwnMessage(all, "me")?.id)
        assertNull(latestOwnMessage(listOf(msg("b", ann, 2)), "me"))
    }

    @Test fun messageReceiptsSplitReadSeenAndEveryone() {
        val m = msg("a", me, 5)
        val receipts = listOf(Receipt("me", 5, 5), Receipt("ann", 9, 6, 20, 30), Receipt("bo", 5, 4, 21, 22), Receipt("cy", 2, 2, 7, 7), Receipt("ghost", 9, 9))
        val r = messageReceipts(m, receipts, listOf(me, ann, bo, cy))
        assertEquals(listOf("ann"), r.read.map { it.deviceId }); assertEquals(listOf("bo"), r.seen.map { it.deviceId }); assertFalse(r.everyone)
        val all = messageReceipts(m, listOf(Receipt("ann", 9, 6), Receipt("bo", 5, 5)), listOf(me, ann, bo))
        assertTrue(all.everyone); assertEquals(listOf("ann", "bo"), all.read.map { it.deviceId })
        // Nobody else in the chat: nothing to be read by.
        assertFalse(messageReceipts(m, listOf(Receipt("me", 5, 5)), listOf(me)).everyone)
    }

    @Test fun editedMessageKeepsItsReceipts() {
        val edited = msg("a", me, 9).copy(createdRev = 5) // edited at rev 9, first posted at 5
        val receipts = listOf(Receipt("ann", 6, 6, 1, 2), Receipt("bo", 6, 4, 1, 2))
        val r = messageReceipts(edited, receipts, listOf(me, ann, bo))
        assertEquals(listOf("ann"), r.read.map { it.deviceId }); assertEquals(listOf("bo"), r.seen.map { it.deviceId })
        assertEquals("Read by Ann · Seen by Bo", receiptLineFor(edited, receipts, listOf(me, ann, bo)))
        assertEquals("", receiptLineFor(edited.copy(createdRev = null), receipts, listOf(me, ann, bo))) // without it: rev 9, nobody has got there
    }

    @Test fun mentionCandidatesFilterByShownName() {
        val members = listOf(me, ann, bo, cy)
        val shown = { p: Peer -> if (p.deviceId == "bo") "Annabel" else p.name }
        assertEquals(listOf("ann", "bo", "cy"), mentionCandidates(members, "me", "", shown).map { it.deviceId })
        assertEquals(listOf("ann", "bo"), mentionCandidates(members, "me", "ann", shown).map { it.deviceId }) // Bo's nickname matches, its name wouldn't
        assertEquals(listOf("bo"), mentionCandidates(members, "me", "BEL", shown).map { it.deviceId }) // case-insensitive, anywhere in the name
        assertEquals(emptyList(), mentionCandidates(members, "me", "me", shown)) // never yourself
    }

    @Test fun messageSegmentsSplitMentionsAndLinks() {
        val segs = messageSegments("hi @Ann see https://x.example/a ok", listOf("ann"), listOf(ann)) { "Annie" }
        assertEquals(
            listOf(Segment("hi "), Segment("@Annie", mention = true), Segment(" see "), Segment("https://x.example/a", link = "https://x.example/a"), Segment(" ok")),
            segs,
        )
        assertEquals(listOf(Segment("plain")), messageSegments("plain", emptyList(), emptyList()))
    }

    @Test fun typedNameWithoutPickingIsNotAMention() {
        val members = listOf(me, ann, bo)
        assertEquals("hi @Ann" to emptyList(), MentionPicks().outgoing("hi @Ann", members, "me"))
        val picks = MentionPicks().with("bo").with("bo")
        assertEquals(listOf("bo"), picks.ids)
        assertEquals("hi @Ann @Bo" to listOf("bo"), picks.outgoing("hi @Ann @Bo", members, "me")) // only the picked one counts
        assertEquals("hi @Ann" to emptyList(), picks.outgoing("hi @Ann", members, "me")) // picked, then edited away
        assertEquals("@Me" to emptyList(), MentionPicks().with("me").outgoing("@Me", members, "me")) // never yourself
        assertEquals(listOf("ann"), MentionPicks.of(msg("a", me, 1).copy(mentions = listOf("ann"))).ids)
    }

    @Test fun duplicateNamesResolveByRecordedIds() {
        val a = Peer("a", "Sam", DeviceKind.PC); val b = Peer("b", "Sam", DeviceKind.PHONE)
        val nick = { p: Peer -> if (p.deviceId == "a") "Sam A" else "Sam B" }
        val shown = mentionSpans("@Sam and @Sam", listOf("a", "b"), listOf(me, a, b), nick)
        assertEquals("@Sam A and @Sam B", shown.text)
        assertEquals(listOf("@Sam A", "@Sam B"), shown.spans.map { shown.text.substring(it) })
        // Only the second was mentioned: the one id gets the first `@Sam`, as nothing else tells them apart; no chip for a stranger.
        val one = mentionSpans("@Sam", listOf("b"), listOf(me, a, b), nick)
        assertEquals("@Sam B", one.text)
        assertEquals(emptyList(), mentionSpans("@Sam", emptyList(), listOf(a, b), nick).spans)
        // On the sending side two picked twins stay apart by their shown names.
        assertEquals("@Sam @Sam" to listOf("a", "b"), outgoingMentions("@Sam A @Sam B", listOf(a to "Sam A", b to "Sam B")))
    }

    private fun summary(id: String, name: String, kind: ChatKind, owner: String = "ann", member: Boolean = true) =
        ChatSummary(id, "s1", "Crew", name, owner, 0, 0, member, null, kind)

    @Test fun openChannelsCannotBeInvitedToLeftOrHandedOver() {
        val open = summary("c", "general", ChatKind.OPEN, owner = "me")
        assertFalse(canInvite(open)); assertFalse(canLeave(open)); assertFalse(canTransferOwner(open, "me"))
        val priv = summary("p", "plans", ChatKind.PRIVATE, owner = "me")
        assertTrue(canInvite(priv)); assertTrue(canLeave(priv)); assertTrue(canTransferOwner(priv, "me"))
        assertFalse(canTransferOwner(priv, "sam"))
    }

    @Test fun creatorAndSpaceAdminManageAChannel() {
        val open = summary("c", "games", ChatKind.OPEN, owner = "ann")
        assertTrue(canManage(open, "ann", false)); assertTrue(canManage(open, "admin", true)); assertFalse(canManage(open, "bob", false))
    }

    @Test fun chatSectionsSplitAndSort() {
        val chats = listOf(
            summary("1", "zeta", ChatKind.PRIVATE), summary("2", "games", ChatKind.OPEN), summary("3", "alpha", ChatKind.PRIVATE),
            summary("4", "General", ChatKind.OPEN), summary("5", "art", ChatKind.OPEN),
        )
        val s = chatSections(chats)
        assertEquals(listOf("art", "games", "General"), s.channels.map { it.name })
        assertEquals(listOf("zeta", "alpha"), s.privateChats.map { it.name })
    }
}
