package app.mjolnir.server

import app.mjolnir.protocol.*
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.websocket.cio.webSocketRawSession
import io.ktor.client.request.*
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.*

/** Long enough for a reconnect under CI load; the "nothing yet" windows stay well inside it. */
private const val GRACE = 1_500L

class VoiceSocketTest {
    private fun RelayTestScope.spaceOf(admin: RegisterResponse, vararg members: RegisterResponse): String {
        val s = store.createSpace(admin.deviceId, "Friends", clock.now)
        members.forEach { store.addMember(s.id, it.deviceId, clock.now) }
        return s.id
    }

    private fun RelayTestScope.lounge(spaceId: String): String = store.voiceRoomsIn(spaceId).single { it.name == "Lounge" }.id

    private suspend fun RelayTestScope.voice(d: RegisterResponse, roomId: String): DefaultClientWebSocketSession =
        client.webSocketSession("/v1/voice/$roomId") { bearerAuth(d.token) }

    /** Reads text frames until a roster equal to [expected] arrives (binary frames in between fail the test). */
    private suspend fun DefaultClientWebSocketSession.awaitRoster(vararg expected: RegisterResponse) {
        val want = expected.map { it.deviceId }
        withTimeout(5_000) {
            while (true) {
                val f = incoming.receive()
                assertIs<Frame.Text>(f, "expected a roster, got a binary frame")
                val roster = MjolnirJson.decodeFromString(VoiceRoster.serializer(), f.readText())
                assertEquals("roster", roster.type)
                if (roster.participants == want) return@withTimeout
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextFrameOrNull(ms: Long): Frame? = withTimeoutOrNull(ms) { incoming.receive() }

    private suspend fun DefaultClientWebSocketSession.nextBinary(): ByteArray = withTimeout(5_000) {
        var got: ByteArray? = null
        while (got == null) (incoming.receive() as? Frame.Binary)?.let { got = it.readBytes() }
        got!!
    }

    private suspend fun DefaultClientWebSocketSession.closedWith(): CloseReason? = withTimeout(5_000) { closeReason.await() }

    private suspend fun DefaultClientWebSocketSession.sendAudio(vararg bytes: Int) = send(Frame.Binary(true, ByteArray(bytes.size) { bytes[it].toByte() }))

    /** A dropped link: the socket goes away without a close frame from the client. */
    private fun DefaultClientWebSocketSession.drop() = cancel()

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun framesReachOnlyTheOtherParticipants() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C"); val d = register("D")
        val sid = spaceOf(a, b, c, d)
        val lounge = lounge(sid)
        val games = store.createVoiceRoom(sid, a.deviceId, "Games", clock.now)!!.id
        val sa = voice(a, lounge); sa.awaitRoster(a)
        val sb = voice(b, lounge); sb.awaitRoster(a, b)
        val sc = voice(c, lounge); sc.awaitRoster(a, b, c)
        val sd = voice(d, games); sd.awaitRoster(d)
        sa.awaitRoster(a, b, c); sb.awaitRoster(a, b, c)

        sa.sendAudio(0, 1, 0xAA)
        assertContentEquals(bytes(0, 0, 1, 0xAA), sb.nextBinary())
        assertContentEquals(bytes(0, 0, 1, 0xAA), sc.nextBinary())
        assertNull(sa.nextFrameOrNull(300))
        assertNull(sd.nextFrameOrNull(300))
        listOf(sa, sb, sc, sd).forEach { it.close() }
    }

    @Test fun speakerIsSetByTheRelay() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C")
        val lounge = lounge(spaceOf(a, b, c))
        val sb = voice(b, lounge); sb.awaitRoster(b)
        val sc = voice(c, lounge); sc.awaitRoster(b, c)
        val sa = voice(a, lounge); sa.awaitRoster(b, c, a)
        sb.awaitRoster(b, c, a)
        // Malformed frames are dropped: no payload, an oversized payload, a lone byte.
        sa.sendAudio(0, 7)
        sa.send(Frame.Binary(true, ByteArray(2 + 1276)))
        sa.sendAudio(9)
        // Whatever A claims, receivers see A's index in the roster (2).
        sa.sendAudio(0, 5, 0x00, 0x01)
        assertContentEquals(bytes(2, 0, 5, 0x00, 0x01), sb.nextBinary())
        val max = ByteArray(2 + 1275) { 3 }
        sa.send(Frame.Binary(true, max))
        assertContentEquals(byteArrayOf(2) + max, sb.nextBinary())
        assertContentEquals(bytes(2, 0, 5, 0x00, 0x01), sc.nextBinary())
        assertContentEquals(byteArrayOf(2) + max, sc.nextBinary())
        // B speaks too: index 0.
        sb.sendAudio(0xFF, 0xFF, 0x42)
        assertContentEquals(bytes(0, 0xFF, 0xFF, 0x42), sc.nextBinary())
        listOf(sa, sb, sc).forEach { it.close() }
    }

    @Test fun oversizedFrameClosesTheSocket() = relayTest {
        val a = register("A")
        val lounge = lounge(spaceOf(a))
        val sa = voice(a, lounge); sa.awaitRoster(a)
        sa.send(Frame.Binary(true, ByteArray(70_000)))
        assertEquals(CloseReason.Codes.TOO_BIG.code, sa.closedWith()?.code)
    }

    @Test fun audioIsIndexedByTheRosterReceiversHold() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C")
        val lounge = lounge(spaceOf(a, b, c))
        val sb = voice(b, lounge); sb.awaitRoster(b)
        val sc = voice(c, lounge); sc.awaitRoster(b, c)
        val sa = voice(a, lounge); sa.awaitRoster(b, c, a)
        sb.awaitRoster(b, c, a)
        // As if C had just left in the hub and the new roster [b, a] had not gone out yet: A keeps index 2.
        deps.voice.leave(lounge, c.deviceId)
        sa.sendAudio(0, 1, 0x11)
        assertContentEquals(bytes(2, 0, 1, 0x11), sb.nextBinary())
        // As if A had just joined and its roster had not gone out yet: A's audio waits for it.
        deps.voice.sentRosters[lounge] = listOf(b.deviceId)
        sa.sendAudio(0, 2, 0x22)
        assertNull(sb.nextFrameOrNull(300))
        listOf(sa, sb, sc).forEach { it.close() }
    }

    @Test fun ninthJoinIsRefused() = relayTest(limits = RelayLimits(maxVoicePerRoom = 2)) {
        val a = register("A"); val b = register("B"); val c = register("C")
        val lounge = lounge(spaceOf(a, b, c))
        val sa = voice(a, lounge); sa.awaitRoster(a)
        val sb = voice(b, lounge); sb.awaitRoster(a, b)
        sa.awaitRoster(a, b)
        val sc = voice(c, lounge)
        assertEquals(CloseReason(4000, "room_full"), sc.closedWith())
        assertEquals(listOf(a.deviceId, b.deviceId), deps.voice.participants(lounge))
        assertNull(sa.nextFrameOrNull(200)) // no roster change
        sa.close(); sb.close()
    }

    @Test fun joiningAnotherRoomReplacesTheFirst() = relayTest(voiceGraceMs = GRACE) {
        val a = register("A"); val b = register("B")
        val sid = spaceOf(a, b)
        val lounge = lounge(sid)
        val games = store.createVoiceRoom(sid, b.deviceId, "Games", clock.now)!!.id
        val sb = voice(b, lounge); sb.awaitRoster(b)
        val first = voice(a, lounge); first.awaitRoster(b, a)
        sb.awaitRoster(b, a)
        withEvents(b) {
            val second = voice(a, games); second.awaitRoster(a)
            assertEquals(CloseReason(4000, "replaced"), first.closedWith())
            sb.awaitRoster(b) // at once, not after the grace
            assertEquals(games, deps.voice.roomOf(a.deviceId))
            assertEquals(listOf(b.deviceId), deps.voice.participants(lounge))
            val changed = buildSet { repeat(2) { add((next() as RelayEvent.VoiceRoomChanged).room.let { it.id to it.participants.map(Peer::deviceId) }) } }
            assertEquals(setOf(lounge to listOf(b.deviceId), games to listOf(a.deviceId)), changed)

            // A second socket for the same room takes over silently.
            val third = voice(a, games); third.awaitRoster(a)
            assertEquals(CloseReason(4000, "replaced"), second.closedWith())
            assertEquals(listOf(a.deviceId), deps.voice.participants(games))
            assertNull(nextOrNull(300))
            third.close()
        }
        sb.close()
    }

    @Test fun slowReceiverDoesNotDelayOthers() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C")
        val lounge = lounge(spaceOf(a, b, c))
        val sa = voice(a, lounge); sa.awaitRoster(a)
        val sb = voice(b, lounge); sb.awaitRoster(a, b)
        val sc = voice(c, lounge); sc.awaitRoster(a, b, c)
        sa.awaitRoster(a, b, c)
        // B never reads from here on. Full-size frames, so B's link backs up; keep sending until it has (at most 5000).
        val payload = ByteArray(1275) { 0x55 }
        var n = 0
        while (n < 200 || (deps.voice.dropped(b.deviceId) == 0 && n < 5000)) {
            val seq = n and 0xFFFF
            sa.send(Frame.Binary(true, byteArrayOf((seq shr 8).toByte(), seq.toByte()) + payload))
            val got = sc.nextBinary()
            assertEquals(0, got[0].toInt())
            assertEquals(seq, ((got[1].toInt() and 0xFF) shl 8) or (got[2].toInt() and 0xFF), "C got frame out of order")
            // Accepted and not yet written: the queue plus the frame being written.
            assertTrue(deps.voice.queued(b.deviceId) <= VoiceLink.AUDIO_QUEUE + 1, "B's queue is capped")
            n++
        }
        // B really backed up and lost frames, alone: C got every one of them.
        assertTrue(deps.voice.dropped(b.deviceId) > 0, "B never backed up in $n frames")
        assertEquals(0, deps.voice.dropped(c.deviceId))
        sa.close(); sc.close(); sb.drop()
    }

    @Test fun reconnectWithinGraceIsSilent() = relayTest(voiceGraceMs = GRACE) {
        val a = register("A"); val b = register("B")
        val lounge = lounge(spaceOf(a, b))
        val sb = voice(b, lounge); sb.awaitRoster(b)
        withEvents(b) {
            val first = voice(a, lounge); first.awaitRoster(b, a)
            sb.awaitRoster(b, a)
            assertIs<RelayEvent.VoiceRoomChanged>(next())
            first.drop()
            delay(GRACE / 3)
            assertEquals(listOf(b.deviceId, a.deviceId), deps.voice.participants(lounge))
            val again = voice(a, lounge); again.awaitRoster(b, a)
            // No leave or join: no roster for B, no room event, and A stays after the grace has passed.
            assertNull(sb.nextFrameOrNull(GRACE))
            assertNull(nextOrNull(200))
            assertEquals(listOf(b.deviceId, a.deviceId), deps.voice.participants(lounge))
            again.sendAudio(0, 1, 0x01)
            assertContentEquals(bytes(1, 0, 1, 0x01), sb.nextBinary())
            again.close()
        }
        sb.close()
    }

    @Test fun droppedParticipantLeavesAfterGrace() = relayTest(voiceGraceMs = GRACE) {
        val a = register("A"); val b = register("B")
        val lounge = lounge(spaceOf(a, b))
        val sb = voice(b, lounge); sb.awaitRoster(b)
        withEvents(b, presence = true) {
            val sa = voice(a, lounge); sa.awaitRoster(b, a)
            sb.awaitRoster(b, a)
            val joined = listOf(next(), next())
            assertTrue(joined.any { it is RelayEvent.PresenceChanged && it.voiceRoomId == lounge })
            assertTrue(joined.any { it is RelayEvent.VoiceRoomChanged })
            sa.drop()
            assertNull(sb.nextFrameOrNull(GRACE / 3))
            assertEquals(listOf(b.deviceId, a.deviceId), deps.voice.participants(lounge))
            sb.awaitRoster(b)
            assertNull(deps.voice.roomOf(a.deviceId))
            val events = listOf(next(), next()) // in either order
            assertTrue(events.any { it is RelayEvent.VoiceRoomChanged && it.room.participants.isEmpty().not() && it.room.participants.single().deviceId == b.deviceId })
            assertTrue(events.any { it is RelayEvent.PresenceChanged && it.deviceId == a.deviceId && it.voiceRoomId == null })
        }
        sb.close()
    }

    @Test fun closingWithLeaveLeavesAtOnce() = relayTest(voiceGraceMs = 60_000) {
        val a = register("A"); val b = register("B")
        val lounge = lounge(spaceOf(a, b))
        val sb = voice(b, lounge); sb.awaitRoster(b)
        val sa = voice(a, lounge); sa.awaitRoster(b, a)
        sb.awaitRoster(b, a)
        sa.close(CloseReason(CloseReason.Codes.NORMAL, "leave"))
        sb.awaitRoster(b)
        assertNull(deps.voice.roomOf(a.deviceId))
        sb.close()
    }

    @Test fun spaceRemovalKicksImmediately() = relayTest(voiceGraceMs = 60_000) {
        val a = register("A"); val b = register("B")
        val sid = spaceOf(a, b)
        val lounge = lounge(sid)
        val sa = voice(a, lounge); sa.awaitRoster(a)
        val sb = voice(b, lounge); sb.awaitRoster(a, b)
        sa.awaitRoster(a, b)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/$sid/members/${b.deviceId}") { bearerAuth(a.token) }.status)
        assertEquals(CloseReason(4000, "removed"), sb.closedWith())
        sa.awaitRoster(a)
        assertEquals(listOf(a.deviceId), deps.voice.participants(lounge))
        // Leaving on one's own kicks the same way.
        store.addMember(sid, b.deviceId, clock.now)
        val again = voice(b, lounge); again.awaitRoster(a, b)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/$sid/members/${b.deviceId}") { bearerAuth(b.token) }.status)
        assertEquals(CloseReason(4000, "removed"), again.closedWith())
        sa.awaitRoster(a)
        sa.close()
    }

    @Test fun roomDeleteClosesItsSockets() = relayTest(voiceGraceMs = 60_000) {
        val a = register("A"); val b = register("B"); val c = register("C")
        val sid = spaceOf(a, b, c)
        val games = store.createVoiceRoom(sid, a.deviceId, "Games", clock.now)!!.id
        val sb = voice(b, games); sb.awaitRoster(b)
        val sc = voice(c, games); sc.awaitRoster(b, c)
        sb.awaitRoster(b, c)
        sc.drop() // in its grace: removed with the room too
        delay(100)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/voice-rooms/$games") { bearerAuth(a.token) }.status)
        assertEquals(CloseReason(4000, "room_deleted"), sb.closedWith())
        assertTrue(deps.voice.participants(games).isEmpty())
        assertNull(deps.voice.roomOf(b.deviceId)); assertNull(deps.voice.roomOf(c.deviceId))
    }

    @Test fun spaceDeleteClosesItsSockets() = relayTest(voiceGraceMs = 60_000) {
        val a = register("A"); val b = register("B")
        val sid = spaceOf(a, b)
        val lounge = lounge(sid)
        val sb = voice(b, lounge); sb.awaitRoster(b)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/$sid") { bearerAuth(a.token) }.status)
        assertEquals(CloseReason(4000, "room_deleted"), sb.closedWith())
        assertNull(deps.voice.roomOf(b.deviceId))
    }

    @Test fun joinRacingARoomDeleteDoesNotStay() = relayTest(voiceGraceMs = 60_000) {
        val a = register("A"); val b = register("B")
        val sid = spaceOf(a, b)
        val games = store.createVoiceRoom(sid, a.deviceId, "Games", clock.now)!!.id
        // The delete commits after the socket's first check, and its hub cleanup ran before the join.
        deps.voice.afterJoin = { roomId, _ -> store.deleteVoiceRoom(roomId) }
        val sb = voice(b, games)
        assertEquals(CloseReason(4000, "room_deleted"), sb.closedWith())
        assertNull(deps.voice.roomOf(b.deviceId))
        assertTrue(deps.voice.participants(games).isEmpty())
    }

    @Test fun joinRacingASpaceRemovalDoesNotStay() = relayTest(voiceGraceMs = 60_000) {
        val a = register("A"); val b = register("B")
        val sid = spaceOf(a, b)
        val lounge = lounge(sid)
        val sa = voice(a, lounge); sa.awaitRoster(a)
        deps.voice.afterJoin = { _, device -> store.removeMember(sid, device, device, clock.now) }
        val sb = voice(b, lounge)
        assertEquals(CloseReason(4000, "removed"), sb.closedWith())
        assertEquals(listOf(a.deviceId), deps.voice.participants(lounge))
        sa.awaitRoster(a)
        sa.close()
    }

    @Test fun nonMemberIsRefused() = relayTest {
        val a = register("A"); val e = register("E")
        val lounge = lounge(spaceOf(a))
        assertEquals(CloseReason(4000, "not_found"), voice(e, lounge).closedWith())
        assertEquals(CloseReason(4000, "not_found"), voice(a, "no-such-room").closedWith())
        assertTrue(deps.voice.participants(lounge).isEmpty())
        assertNull(deps.voice.roomOf(e.deviceId))
    }

    @Test fun guestIsRefused() = relayTest {
        val a = register("A")
        val lounge = lounge(spaceOf(a))
        val (_, guest) = guestOf(a)
        // The guest guard turns it away before the upgrade.
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/voice/$lounge") { bearerAuth(guest.token) }.status)
        assertFailsWith<Exception> { voice(guest, lounge) }
        assertNull(deps.voice.roomOf(guest.deviceId))
    }

    @Test fun presenceCarriesVoiceRoom() = relayTest(voiceGraceMs = 60_000) {
        val a = register("A"); val b = register("B"); val x = register("X")
        val s1 = spaceOf(a, b)
        val s2 = spaceOf(x, a) // X shares another space with A, not the room's
        val lounge = lounge(s1)
        withEvents(a) { withEvents(b, presence = true) { val eb = this
            withEvents(x, presence = true) { val ex = this
                while (eb.nextOrNull(300) != null) Unit // A's own online reports
                while (ex.nextOrNull(300) != null) Unit
                val sa = voice(a, lounge); sa.awaitRoster(a)
                var seen: RelayEvent.PresenceChanged? = null
                while (seen == null) seen = eb.next() as? RelayEvent.PresenceChanged
                assertEquals(a.deviceId, seen.deviceId); assertEquals(lounge, seen.voiceRoomId)
                // Only members of the room's space learn which room it is.
                val inS1 = client.get("/v1/spaces") { bearerAuth(b.token) }.body<List<Space>>().single { it.id == s1 }.members
                assertEquals(lounge, inS1.single { it.deviceId == a.deviceId }.voiceRoomId)
                assertNull(inS1.single { it.deviceId == b.deviceId }.voiceRoomId)
                val inS2 = client.get("/v1/spaces") { bearerAuth(x.token) }.body<List<Space>>().single { it.id == s2 }.members
                assertNull(inS2.single { it.deviceId == a.deviceId }.voiceRoomId)
                // X shares a space with A but not the room's, and A was online already: X hears nothing at all.
                assertNull(ex.nextOrNull(300))

                sa.close(CloseReason(CloseReason.Codes.NORMAL, "leave"))
                seen = null
                while (seen == null) seen = eb.next() as? RelayEvent.PresenceChanged
                assertEquals(a.deviceId, seen.deviceId); assertNull(seen.voiceRoomId)
                assertNull(client.get("/v1/spaces") { bearerAuth(b.token) }.body<List<Space>>().single { it.id == s1 }
                    .members.single { it.deviceId == a.deviceId }.voiceRoomId)
                assertNull(ex.nextOrNull(300))
            }
        } }
    }

    private suspend fun EventReader.nextPresence(): RelayEvent.PresenceChanged {
        while (true) (next() as? RelayEvent.PresenceChanged)?.let { return it }
    }

    private suspend fun RelayTestScope.memberView(viewer: RegisterResponse, spaceId: String, of: RegisterResponse): Peer =
        client.get("/v1/spaces") { bearerAuth(viewer.token) }.body<List<Space>>().single { it.id == spaceId }.members.single { it.deviceId == of.deviceId }

    @Test fun voiceAloneKeepsADeviceOnline() = relayTest(voiceGraceMs = 60_000) {
        val a = register("A"); val b = register("B"); val x = register("X")
        val sid = spaceOf(a, b)
        spaceOf(x, b)
        val lounge = lounge(sid)
        withEvents(a, presence = true) { val ea = this
            withEvents(x, presence = true) { val ex = this
                // B has no event socket: joining voice brings it online for every co-member.
                val sb = voice(b, lounge); sb.awaitRoster(b)
                assertEquals(RelayEvent.PresenceChanged(b.deviceId, true, null, lounge), ea.nextPresence())
                assertEquals(RelayEvent.PresenceChanged(b.deviceId, true, null, null), ex.nextPresence())
                assertTrue(memberView(a, sid, b).online)
                clock.now += 5_000
                sb.close(VOICE_LEAVE)
                assertEquals(RelayEvent.PresenceChanged(b.deviceId, false, clock.now, null), ea.nextPresence())
                assertEquals(RelayEvent.PresenceChanged(b.deviceId, false, clock.now, null), ex.nextPresence())
                assertFalse(memberView(a, sid, b).online)
                assertEquals(clock.now, store.lastSeen(b.deviceId))
            }
        }
    }

    @Test fun closingTheEventSocketInVoiceStaysOnline() = relayTest(voiceGraceMs = 60_000, presenceGraceMs = 300) {
        val a = register("A"); val b = register("B")
        val sid = spaceOf(a, b)
        val lounge = lounge(sid)
        withEvents(a, presence = true) {
            val sb = voice(b, lounge); sb.awaitRoster(b)
            assertEquals(lounge, nextPresence().voiceRoomId)
            withEvents(b) { }
            // B's event socket came and went; its voice link keeps it online past the offline grace.
            assertTrue(nextPresence().online) // the event socket's own online report
            assertNull(nextOrNull(1_200))
            assertTrue(memberView(a, sid, b).online)
            sb.close(VOICE_LEAVE)
            val off = nextPresence()
            assertFalse(off.online); assertNull(off.voiceRoomId); assertEquals(clock.now, off.lastSeenMs)
        }
    }

    @Test fun halfOpenVoiceLinkIsNoticed() = relayTest(voiceGraceMs = 60_000, voicePingMs = 200) {
        val a = register("A")
        val lounge = lounge(spaceOf(a))
        // A raw session never answers pings, like a peer whose network vanished.
        val raw = client.webSocketRawSession(path = "/v1/voice/$lounge") { bearerAuth(a.token) }
        withTimeout(5_000) { while (deps.voice.linkOf(a.deviceId) == null) delay(10) }
        withTimeout(5_000) { while (deps.voice.linkOf(a.deviceId) != null) delay(20) }
        assertEquals(lounge, deps.voice.roomOf(a.deviceId)) // in its grace now
        raw.cancel()
    }

    @Test fun onlyTheSocketRoutesSkipTheBodyLimit() = relayTest {
        // The test client may not set Upgrade itself, so the predicate the body limit uses is checked directly.
        assertTrue(isSocketUpgrade(HttpMethod.Get, "/v1/ws", "websocket"))
        assertTrue(isSocketUpgrade(HttpMethod.Get, "/v1/voice/r1", "WebSocket"))
        assertFalse(isSocketUpgrade(HttpMethod.Post, "/v1/devices", "websocket"))
        assertFalse(isSocketUpgrade(HttpMethod.Post, "/v1/voice/r1", "websocket"))
        assertFalse(isSocketUpgrade(HttpMethod.Get, "/v1/spaces", "websocket"))
        assertFalse(isSocketUpgrade(HttpMethod.Get, "/v1/ws", null))
        // And a large JSON body without it is still refused.
        val r = client.post("/v1/devices") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(ByteArray(Limits.MAX_BODY_BYTES.toInt() + 1024) { ' '.code.toByte() })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }
}
