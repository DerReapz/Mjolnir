package app.mjolnir.server

import app.mjolnir.protocol.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class VoiceRoomsTest {
    private suspend fun RelayTestScope.spaceOf(admin: RegisterResponse, vararg members: RegisterResponse): String {
        val s = store.createSpace(admin.deviceId, "Friends", clock.now)
        members.forEach { store.addMember(s.id, it.deviceId, clock.now) }
        return s.id
    }

    private suspend fun RelayTestScope.rooms(d: RegisterResponse, spaceId: String): HttpResponse =
        client.get("/v1/spaces/$spaceId/voice-rooms") { bearerAuth(d.token) }

    private suspend fun RelayTestScope.create(d: RegisterResponse, spaceId: String, name: String): HttpResponse =
        client.post("/v1/spaces/$spaceId/voice-rooms") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(CreateVoiceRoomRequest(name)) }

    private suspend fun RelayTestScope.rename(d: RegisterResponse, roomId: String, name: String): HttpResponse =
        client.patch("/v1/voice-rooms/$roomId") { bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(CreateVoiceRoomRequest(name)) }

    @Test fun newSpaceHasLounge() = relayTest {
        val a = register("A")
        val s = client.post("/v1/spaces") { bearerAuth(a.token); contentType(ContentType.Application.Json); setBody(CreateSpaceRequest("Home")) }.body<Space>()
        val list = rooms(a, s.id).body<List<VoiceRoom>>()
        assertEquals(listOf("Lounge"), list.map { it.name })
        assertEquals(a.deviceId, list.single().creatorId)
        assertEquals(s.id, list.single().spaceId)
        assertTrue(list.single().participants.isEmpty())
    }

    @Test fun roomsCarryTheRelaysLimit() = relayTest(limits = RelayLimits(maxVoicePerRoom = 12)) {
        val a = register("A")
        val s = spaceOf(a)
        assertEquals(listOf(12), rooms(a, s).body<List<VoiceRoom>>().map { it.maxParticipants })
        assertEquals(12, create(a, s, "Games").body<VoiceRoom>().maxParticipants)
    }

    @Test fun oldRelayRoomWithoutLimitReadsAsEight() {
        val json = """{"id":"r","spaceId":"s","name":"Lounge","creatorId":"a"}"""
        assertEquals(8, MjolnirJson.decodeFromString(VoiceRoom.serializer(), json).maxParticipants)
    }

    @Test fun backfillAddsLoungeOnce() {
        val file = createTempDirectory().resolve("pre.db").toString()
        val st = Store("jdbc:sqlite:$file")
        val a = st.createDevice("a", DeviceKind.PC, "hash-a", 1000L)
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use {
                it.executeUpdate("INSERT INTO spaces(id,name,admin_id,created_at) VALUES('old','Old','${a.id}',5)")
                it.executeUpdate("INSERT INTO space_members(space_id,device_id,joined_at) VALUES('old','${a.id}',5)")
            }
        }
        assertEquals(1, st.ensureDefaultVoiceRooms(50))
        val lounge = st.voiceRoomsIn("old").single()
        assertEquals("Lounge", lounge.name); assertEquals(a.id, lounge.creatorId)
        assertEquals(0, st.ensureDefaultVoiceRooms(60))
        assertTrue(st.deleteVoiceRoom(lounge.id))
        assertEquals(0, st.ensureDefaultVoiceRooms(70))
        assertTrue(st.voiceRoomsIn("old").isEmpty())
    }

    @Test fun createRenameDeleteRules() = relayTest {
        val a = register("A"); val b = register("B"); val c = register("C")
        val sid = spaceOf(a, b, c)
        withEvents(a) { withEvents(c) {
            val made = create(b, sid, " Games ")
            assertEquals(HttpStatusCode.Created, made.status)
            val room = made.body<VoiceRoom>()
            assertEquals("Games", room.name); assertEquals(b.deviceId, room.creatorId)
            assertEquals(room, (next() as RelayEvent.VoiceRoomChanged).room)
            assertEquals(HttpStatusCode.BadRequest, create(b, sid, "  ").status)

            // A plain member cannot touch someone else's room; the creator and the admin can.
            assertEquals(HttpStatusCode.Forbidden, rename(c, room.id, "Mine").status)
            assertEquals(HttpStatusCode.Forbidden, client.delete("/v1/voice-rooms/${room.id}") { bearerAuth(c.token) }.status)
            assertEquals("Chat", rename(b, room.id, "Chat").body<VoiceRoom>().name)
            assertEquals("Chat", (next() as RelayEvent.VoiceRoomChanged).room.name)
            assertEquals("Talk", rename(a, room.id, "Talk").body<VoiceRoom>().name)

            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/voice-rooms/${room.id}") { bearerAuth(a.token) }.status)
            assertEquals("Talk", (next() as RelayEvent.VoiceRoomChanged).room.name) // a's own rename event
            assertEquals(RelayEvent.VoiceRoomDeleted(sid, room.id), next())
        } }
        assertEquals(listOf("Lounge"), rooms(a, sid).body<List<VoiceRoom>>().map { it.name })
    }

    @Test fun nonMemberCannotSeeRooms() = relayTest {
        val a = register("A"); val out = register("Out")
        val sid = spaceOf(a)
        val room = store.voiceRoomsIn(sid).single()
        assertEquals(HttpStatusCode.NotFound, rooms(out, sid).status)
        assertEquals(HttpStatusCode.NotFound, create(out, sid, "X").status)
        assertEquals(HttpStatusCode.NotFound, rename(out, room.id, "X").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/voice-rooms/${room.id}") { bearerAuth(out.token) }.status)
        assertEquals(HttpStatusCode.NotFound, rooms(a, "nope").status)
    }

    @Test fun deletingASpaceDeletesItsRooms() = relayTest {
        val a = register("A")
        val sid = spaceOf(a)
        val room = store.voiceRoomsIn(sid).single()
        deps.voice.join(room.id, a.deviceId, 8)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/$sid") { bearerAuth(a.token) }.status)
        assertNull(store.voiceRoom(room.id))
        assertTrue(store.voiceRoomsIn(sid).isEmpty())
        assertNull(deps.voice.roomOf(a.deviceId))
    }

    @Test fun leavingTheSpaceLeavesItsRooms() = relayTest {
        val a = register("A"); val b = register("B")
        val sid = spaceOf(a, b)
        val room = store.voiceRoomsIn(sid).single()
        deps.voice.join(room.id, b.deviceId, 8)
        val removed = mutableListOf<Pair<String, List<String>>>()
        deps.voice.onRemoved = { r, d -> removed += r to d }
        withEvents(a) {
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/$sid/members/${b.deviceId}") { bearerAuth(b.token) }.status)
            var changed: VoiceRoom? = null
            while (changed == null) (next() as? RelayEvent.VoiceRoomChanged)?.let { changed = it.room }
            assertTrue(changed!!.participants.isEmpty())
        }
        assertNull(deps.voice.roomOf(b.deviceId))
        assertEquals(listOf(room.id to listOf(b.deviceId)), removed)
    }

    @Test fun hubCapsAndMoves() {
        val hub = VoiceHub()
        assertEquals(VoiceHub.JoinResult.JOINED, hub.join("r1", "a", 2))
        assertEquals(VoiceHub.JoinResult.JOINED, hub.join("r1", "b", 2))
        assertEquals(VoiceHub.JoinResult.FULL, hub.join("r1", "c", 2))
        assertNull(hub.roomOf("c"))
        assertEquals(VoiceHub.JoinResult.JOINED, hub.join("r1", "a", 2)) // already there
        assertEquals(listOf("a", "b"), hub.participants("r1"))

        assertEquals(VoiceHub.JoinResult.MOVED("r1"), hub.join("r2", "a", 2))
        assertEquals("r2", hub.roomOf("a"))
        assertEquals(listOf("b"), hub.participants("r1"))
        assertEquals(listOf("a"), hub.participants("r2"))
        // A full target leaves the device where it was.
        assertEquals(VoiceHub.JoinResult.JOINED, hub.join("r2", "c", 2))
        assertEquals(VoiceHub.JoinResult.FULL, hub.join("r2", "b", 2))
        assertEquals("r1", hub.roomOf("b"))
    }

    @Test fun hubLeaveIsIdempotent() {
        val hub = VoiceHub()
        hub.join("r1", "a", 8)
        assertTrue(hub.leave("r1", "a"))
        assertFalse(hub.leave("r1", "a"))
        assertFalse(hub.leave("other", "a"))
        assertNull(hub.roomOf("a"))
        assertTrue(hub.participants("r1").isEmpty())
    }
}
