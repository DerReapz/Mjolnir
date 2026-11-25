package app.mjolnir.client.voice

import app.mjolnir.client.ClientTestScope
import app.mjolnir.client.RelayClient
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.TestDevice
import app.mjolnir.client.assertKind
import app.mjolnir.client.clientTest
import app.mjolnir.client.installMjolnirDefaults
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.server.RelayDeps
import app.mjolnir.server.RelayLimits
import app.mjolnir.server.Store
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The voice room routes and the voice socket of [RelayClient], against the real relay. */
class RelayClientVoiceTest {
    private suspend fun ClientTestScope.space(): Triple<TestDevice, TestDevice, String> {
        val a = newDevice("A", DeviceKind.PC); val b = newDevice("B", DeviceKind.PHONE)
        val space = a.api.createSpace("Friends")
        val claim = b.api.claimPairingCode(a.api.createInvite(space.id).code, CodeKind.SPACE_INVITE)
        a.api.acceptPairing(claim.pairingId)
        return Triple(a, b, space.id)
    }

    private suspend fun eventually(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        withTimeoutOrNull(timeoutMs) { while (!cond()) delay(5) }
        assertTrue(cond(), what)
    }

    @Test fun roomRoutes() = clientTest {
        val (a, b, spaceId) = space()
        val lounge = a.api.voiceRooms(spaceId).single()
        assertEquals("Lounge", lounge.name); assertEquals(spaceId, lounge.spaceId)
        val games = b.api.createVoiceRoom(spaceId, "Games")
        assertEquals(b.id, games.creatorId)
        assertEquals(listOf("Lounge", "Games"), a.api.voiceRooms(spaceId).map { it.name })
        assertEquals("Board games", b.api.renameVoiceRoom(games.id, "Board games").name)
        assertKind(RelayErrorKind.NOT_PAIRED) { b.api.renameVoiceRoom(lounge.id, "Mine") } // 403 not_owner
        assertKind(RelayErrorKind.INVALID) { b.api.createVoiceRoom(spaceId, "  ") }
        b.api.deleteVoiceRoom(games.id)
        assertEquals(listOf("Lounge"), a.api.voiceRooms(spaceId).map { it.name })
        assertKind(RelayErrorKind.NOT_FOUND) { a.api.voiceRooms("nope") }
    }

    @Test fun oldRelayIsRelayOutdated() = testApplication {
        application { routing { } }
        val api = RelayClient(createClient { installMjolnirDefaults() }, "http://localhost", "1.0.0") { "t" }
        assertKind(RelayErrorKind.RELAY_OUTDATED) { api.voiceRooms("s") }
    }

    @Test fun rosterFramesAndLeave() = clientTest {
        val (a, b, spaceId) = space()
        val room = a.api.voiceRooms(spaceId).single().id
        val rosterA = Collections.synchronizedList(mutableListOf<List<String>>())
        val framesB = Collections.synchronizedList(mutableListOf<Triple<Int, Int, List<Byte>>>())
        val rosterB = Collections.synchronizedList(mutableListOf<List<String>>())
        val la = a.api.openVoice(room, { rosterA += it }, { _, _, _ -> })
        eventually("A's roster") { rosterA.lastOrNull() == listOf(a.id) }
        val lb = b.api.openVoice(room, { rosterB += it }, { i, s, o -> framesB += Triple(i, s, o.toList()) })
        eventually("both rosters") { rosterA.lastOrNull() == listOf(a.id, b.id) && rosterB.lastOrNull() == listOf(a.id, b.id) }
        la.send(65535, byteArrayOf(1, 2, 3))
        eventually("B gets A's frame") { framesB.isNotEmpty() }
        assertEquals(Triple(0, 65535, listOf<Byte>(1, 2, 3)), framesB.single())
        la.close()
        assertEquals(null, withTimeout(10_000) { la.closeReason.await() })
        // A deliberate leave: the relay drops A at once, well inside its 10 s grace.
        eventually("A left at once", timeoutMs = 5_000) { rosterB.lastOrNull() == listOf(b.id) }
        lb.close()
    }

    @Test fun relayCloseReasonIsReported() = clientTest(
        RelayDeps(store = Store("jdbc:sqlite::memory:"), version = "1.0.0", limits = RelayLimits(maxVoicePerRoom = 1)),
    ) {
        val (a, b, spaceId) = space()
        val room = a.api.voiceRooms(spaceId).single().id
        val la = a.api.openVoice(room, {}, { _, _, _ -> })
        eventually("A is in") { deps.voice.participants(room) == listOf(a.id) }
        val lb = b.api.openVoice(room, {}, { _, _, _ -> })
        assertEquals("room_full", withTimeout(10_000) { lb.closeReason.await() })
        assertEquals("not_found", withTimeout(10_000) { b.api.openVoice("no-such-room", {}, { _, _, _ -> }).closeReason.await() })
        la.close()
    }

    /** Ruling V5: the relay's 1 MiB request-body limit never applies to socket traffic; a real call sends megabytes. */
    @Test fun moreThanAMebibyteKeepsBeingForwarded() = clientTest {
        val (a, b, spaceId) = space()
        val room = a.api.voiceRooms(spaceId).single().id
        val received = AtomicInteger(); val receivedBytes = AtomicLong(); val lastSeq = AtomicInteger(-1)
        val rosterB = Collections.synchronizedList(mutableListOf<List<String>>())
        val la = a.api.openVoice(room, {}, { _, _, _ -> })
        val lb = b.api.openVoice(room, { rosterB += it }, { _, seq, opus ->
            received.incrementAndGet(); receivedBytes.addAndGet(opus.size.toLong()); lastSeq.set(seq)
        })
        eventually("both in") { rosterB.lastOrNull()?.toSet() == setOf(a.id, b.id) }
        val frames = 1_200; val size = 1_275 // 1.46 MiB in all, every packet the largest Opus allows
        var sentBytes = 0L
        for (i in 0 until frames) {
            la.send(i, ByteArray(size) { (i + it).toByte() })
            sentBytes += 2 + size
            // Paced in batches well under the relay's per-receiver queue, so nothing is dropped for being slow.
            if (i % 8 == 7) eventually("batch ${i / 8} forwarded (got ${received.get()})") { received.get() == i + 1 }
        }
        eventually("all forwarded") { received.get() == frames }
        assertTrue(sentBytes > 1024 * 1024, "sent $sentBytes bytes")
        assertEquals(frames.toLong() * size, receivedBytes.get())
        assertEquals(frames - 1, lastSeq.get())
        la.close(); lb.close()
    }
}
