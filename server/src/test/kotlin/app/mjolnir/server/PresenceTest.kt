package app.mjolnir.server

import app.mjolnir.protocol.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Long enough for a WebSocket reconnect under CI load; "nothing yet" windows stay well inside it. */
private const val GRACE = 1_500L

class PresenceTest {
    private suspend fun RelayTestScope.spaceWith(admin: RegisterResponse, vararg joiners: RegisterResponse): Space {
        val s = client.post("/v1/spaces") {
            bearerAuth(admin.token); contentType(ContentType.Application.Json); setBody(CreateSpaceRequest("Friends"))
        }.body<Space>()
        for (j in joiners) {
            val code = client.post("/v1/spaces/${s.id}/invites") { bearerAuth(admin.token) }.body<PairingCodeResponse>().code
            val claim = client.post("/v1/pairings/claim") {
                bearerAuth(j.token); contentType(ContentType.Application.Json); setBody(ClaimRequest(code, CodeKind.SPACE_INVITE))
            }.body<ClaimResponse>()
            check(client.post("/v1/pairings/${claim.pairingId}/accept") { bearerAuth(admin.token) }.status == HttpStatusCode.NoContent)
        }
        return s
    }

    private suspend fun RelayTestScope.members(d: RegisterResponse, spaceId: String): List<Peer> =
        client.get("/v1/spaces") { bearerAuth(d.token) }.body<List<Space>>().single { it.id == spaceId }.members

    /** The presence events that arrive within [ms]. */
    private suspend fun EventReader.presenceWithin(ms: Long): List<RelayEvent.PresenceChanged> {
        val got = mutableListOf<RelayEvent.PresenceChanged>()
        val until = System.currentTimeMillis() + ms
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return got
            val e = nextOrNull(left) ?: return got
            if (e is RelayEvent.PresenceChanged) got += e
        }
    }

    @Test fun coMembersAreTheDevicesSharingASpace() = relayTest(presenceGraceMs = GRACE) {
        val a = register("A"); val b = register("B"); val c = register("C"); val d = register("D")
        spaceWith(a, b); pair(a, c); spaceWith(d)
        assertEquals(setOf(b.deviceId), deps.coMembers(a.deviceId))
        assertEquals(setOf(a.deviceId), deps.coMembers(b.deviceId))
        assertTrue(deps.coMembers(c.deviceId).isEmpty())
        assertTrue(deps.coMembers(d.deviceId).isEmpty())
    }

    @Test fun connectEmitsOnlineToCoMembersOnly() = relayTest(presenceGraceMs = GRACE) {
        val a = register("A"); val b = register("B"); val c = register("C"); val d = register("D")
        spaceWith(a, b); pair(a, c); spaceWith(d)
        withEvents(a, presence = true) { val ea = this
            withEvents(c, presence = true) { val ec = this
                withEvents(d, presence = true) { val ed = this
                    withEvents(b, presence = true) {
                        assertEquals(RelayEvent.PresenceChanged(b.deviceId, true), ea.next())
                        assertNull(ec.nextOrNull(200))
                        assertNull(ed.nextOrNull(50))
                        assertNull(ea.nextOrNull(50))
                    }
                }
            }
        }
    }

    @Test fun disconnectEmitsOfflineAfterGrace() = relayTest(presenceGraceMs = GRACE) {
        val a = register("A"); val b = register("B")
        spaceWith(a, b)
        clock.now = 1_700_000_123_000L
        withEvents(a, presence = true) {
            withEvents(b) {}
            val closed = System.currentTimeMillis()
            assertEquals(RelayEvent.PresenceChanged(b.deviceId, true), next())
            assertNull(nextOrNull(300)) // not before the grace period
            assertEquals(RelayEvent.PresenceChanged(b.deviceId, false, clock.now), next())
            // The server closes its side after the client, so the report can only come later than the grace from here.
            assertTrue(System.currentTimeMillis() - closed >= GRACE - 100)
        }
    }

    @Test fun quickReconnectSendsNoOffline() = relayTest(presenceGraceMs = GRACE) {
        val a = register("A"); val b = register("B")
        spaceWith(a, b)
        withEvents(a, presence = true) { val ea = this
            withEvents(b) {}
            withEvents(b) {
                val seen = ea.presenceWithin(GRACE + 1_000)
                assertTrue(seen.isNotEmpty() && seen.all { it.online }, "$seen")
            }
        }
    }

    @Test fun secondConnectionKeepsOnline() = relayTest(presenceGraceMs = GRACE) {
        val a = register("A"); val b = register("B")
        spaceWith(a, b)
        withEvents(a, presence = true) { val ea = this
            withEvents(b) {
                assertEquals(RelayEvent.PresenceChanged(b.deviceId, true), ea.next())
                withEvents(b) {}
                assertEquals(emptyList(), ea.presenceWithin(GRACE + 1_000))
                assertTrue(deps.hub.isConnected(b.deviceId))
            }
        }
    }

    @Test fun spaceMembersCarryPresence() = relayTest(presenceGraceMs = GRACE) {
        val a = register("A"); val b = register("B")
        val s = spaceWith(a, b)
        withEvents(a, presence = true) {
            withEvents(b) {
                val live = members(a, s.id).single { it.deviceId == b.deviceId }
                assertTrue(live.online)
                assertTrue(members(b, s.id).single { it.deviceId == a.deviceId }.online)
            }
            assertEquals(RelayEvent.PresenceChanged(b.deviceId, true), next())
            assertEquals(RelayEvent.PresenceChanged(b.deviceId, false, clock.now), next())
            val gone = members(a, s.id).single { it.deviceId == b.deviceId }
            assertFalse(gone.online)
            assertEquals(clock.now, gone.lastSeenMs)
        }
        // Peers outside Space.members keep the defaults.
        val peer = client.get("/v1/peers") { bearerAuth(a.token) }.body<List<Peer>>().single()
        assertFalse(peer.online); assertNull(peer.lastSeenMs)
    }

    @Test fun guestsHaveNoPresence() = relayTest(presenceGraceMs = GRACE) {
        val a = register("A"); val b = register("B")
        spaceWith(a, b)
        val (_, guest) = guestOf(a)
        withEvents(a, presence = true) { val ea = this
            withEvents(guest) {}
            assertEquals(emptyList(), ea.presenceWithin(GRACE + 1_000))
        }
    }

    /**
     * The offline check fires just as the device reconnects: whichever way the two interleave, the last report is online.
     * The hook pauses the fired check (after it found the device gone) while another thread reconnects; the reconnect's
     * online report must wait for the offline one instead of slipping in before it.
     */
    @Test fun lateOfflineCheckNeverOverridesAFreshOnline() = relayTest(presenceGraceMs = 200) {
        val a = register("A"); val b = register("B")
        spaceWith(a, b)
        val sink = object : EventSink {
            override suspend fun sendText(text: String) {}
            override fun abort() {}
        }
        withEvents(a, presence = true) {
            withEvents(b) {}
            assertEquals(RelayEvent.PresenceChanged(b.deviceId, true), next())
            var raced = false
            deps.presence.beforeOfflineReport = { id ->
                if (!raced) {
                    raced = true
                    val reconnect = Thread { if (deps.hub.add(id, sink)) deps.onConnected(id) }
                    reconnect.start()
                    reconnect.join(500) // without the lock it finishes here, reporting online before the offline below
                }
            }
            assertEquals(RelayEvent.PresenceChanged(b.deviceId, false, clock.now), next())
            assertEquals(RelayEvent.PresenceChanged(b.deviceId, true), next())
            assertTrue(raced)
            assertTrue(deps.hub.isConnected(b.deviceId))
            deps.hub.remove(b.deviceId, sink)
        }
    }
}
