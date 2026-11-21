package app.mjolnir.server

import app.mjolnir.protocol.ClaimRequest
import app.mjolnir.protocol.RegisterResponse
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.CreateSpaceRequest
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.RoomStatus
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.TicketRequest
import app.mjolnir.protocol.TicketResponse
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomRoutesTest {
    private suspend fun RelayTestScope.ticketResponse(device: RegisterResponse, req: TicketRequest): HttpResponse =
        client.post("/v1/guest/ticket") { bearerAuth(device.token); contentType(ContentType.Application.Json); setBody(req) }

    private suspend fun RelayTestScope.ticket(device: RegisterResponse, req: TicketRequest): String {
        val r = ticketResponse(device, req)
        check(r.status == HttpStatusCode.OK) { "ticket failed: ${r.status}" }
        return r.body<TicketResponse>().ticket
    }

    private suspend fun HttpResponse.error(): String = body<ErrorResponse>().error

    @Test fun createJoinExchangeEnd() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val room = openRoom(owner)
        assertTrue(room.secret.length >= 22, "at least 128 bits")
        assertTrue(room.secret.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "URL-safe base64")
        assertEquals(clock.now + Limits.GUEST_ROOM_TTL_MS, room.expiresAtEpochMs)

        val joined = withEvents(owner) {
            val j = join(room.secret)
            val ev = next() as RelayEvent.GuestJoined
            assertEquals(room.roomId, ev.roomId)
            assertEquals(Peer(j.deviceId, "Guest", DeviceKind.GUEST), ev.guest)
            j
        }
        assertEquals(Peer(owner.deviceId, "Desk", DeviceKind.PC), joined.owner)
        assertEquals(room.roomId, joined.roomId)
        assertEquals(room.expiresAtEpochMs, joined.expiresAtEpochMs)
        assertEquals(100, joined.maxFileMb)
        val guest = joined.asDevice()

        val status = client.get("/v1/rooms/current") { bearerAuth(owner.token) }.body<RoomStatus>()
        assertEquals(RoomStatus(room.roomId, room.expiresAtEpochMs, clock.now, Peer(guest.deviceId, "Guest", DeviceKind.GUEST)), status)

        // Guest -> owner.
        val fromGuestFile = upload(guest, "Trip/a.txt", "hi".toByteArray(), "text/plain")
        assertEquals(HttpStatusCode.Accepted, sendFiles(guest, owner.deviceId, listOf(fromGuestFile.id)).status)
        val fromGuest = inbox(owner).single()
        assertEquals(DeviceKind.GUEST, fromGuest.from.kind)
        assertEquals(listOf(fromGuestFile), fromGuest.files)

        // Owner -> guest: a file and a link.
        val toGuestFile = upload(owner, "b.txt", "yo".toByteArray(), "text/plain")
        assertEquals(HttpStatusCode.Accepted, sendFiles(owner, guest.deviceId, listOf(toGuestFile.id)).status)
        assertEquals(HttpStatusCode.Accepted, sendStatus(owner, guest.deviceId, listOf(Tab("https://example.com"))))
        val forGuest = inbox(guest)
        assertEquals(2, forGuest.size)
        assertEquals(listOf(toGuestFile), forGuest[0].files)
        assertContentEquals("yo".toByteArray(), client.get("/v1/files/${toGuestFile.id}") { bearerAuth(guest.token) }.readRawBytes())

        withEvents(owner) {
            assertEquals(HttpStatusCode.NoContent, endRoom(owner, room.roomId))
            assertEquals(RelayEvent.RoomEnded(room.roomId), next())
        }
        assertEquals(HttpStatusCode.NoContent, client.get("/v1/rooms/current") { bearerAuth(owner.token) }.status)
        assertNull(store.device(guest.deviceId))
        assertFalse(store.areLinked(owner.deviceId, guest.deviceId))
        assertFalse(deps.files.file(toGuestFile.id).exists())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/inbox") { bearerAuth(guest.token) }.status)
        assertTrue(peers(owner).isEmpty())
        // What the guest sent stays with the owner, still labelled as from a guest.
        val kept = inbox(owner).single()
        assertEquals(fromGuest.id, kept.id)
        assertEquals(DeviceKind.GUEST, kept.from.kind)
        assertContentEquals("hi".toByteArray(), client.get("/v1/files/${fromGuestFile.id}") { bearerAuth(owner.token) }.readRawBytes())
        assertEquals(HttpStatusCode.Gone, joinResponse(room.secret).status)
    }

    @Test fun secondRoomRefused() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val first = openRoom(owner)
        val second = openRoomResponse(owner)
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals("room_open", second.error())
        // Another device has its own room.
        openRoom(register("Other", DeviceKind.PC))
        assertEquals(HttpStatusCode.NoContent, endRoom(owner, first.roomId))
        val third = openRoom(owner)
        // An expired room no longer blocks a new one, even before the sweeper has run.
        join(third.secret)
        clock.now += Limits.GUEST_ROOM_TTL_MS
        val fourth = openRoom(owner)
        assertEquals(clock.now + Limits.GUEST_ROOM_TTL_MS, fourth.expiresAtEpochMs)
        assertEquals(2, store.rowCount("devices"), "the expired room's guest is gone")
    }

    @Test fun joinTwiceIsInUse() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val room = openRoom(owner)
        join(room.secret)
        val again = joinResponse(room.secret)
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("in_use", again.error())
        assertEquals(2, store.rowCount("devices"))
    }

    @Test fun rejoinWithStoredTokenWorks() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (_, guest) = guestOf(owner)
        val me = client.get("/v1/devices/me") { bearerAuth(guest.token) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(Peer(guest.deviceId, "Guest", DeviceKind.GUEST), me.body<Peer>())
        clock.now += Limits.GUEST_ROOM_TTL_MS - 1
        assertEquals(HttpStatusCode.OK, client.get("/v1/devices/me") { bearerAuth(guest.token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/inbox") { bearerAuth(guest.token) }.status)
    }

    @Test fun expiredJoinIsClosed() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val room = openRoom(owner)
        clock.now += Limits.GUEST_ROOM_TTL_MS
        val expired = joinResponse(room.secret)
        assertEquals(HttpStatusCode.Gone, expired.status)
        assertEquals("closed", expired.error())
        assertEquals(HttpStatusCode.Gone, joinResponse("no-such-secret").status)
        val ended = openRoom(owner)
        endRoom(owner, ended.roomId)
        assertEquals(HttpStatusCode.Gone, joinResponse(ended.secret).status)
        assertEquals(1, store.rowCount("devices"))
    }

    @Test fun tokenRejectedAfterEnd() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (room, guest) = guestOf(owner)
        val file = upload(owner, "b.txt", "yo".toByteArray())
        sendFiles(owner, guest.deviceId, listOf(file.id))
        assertEquals(HttpStatusCode.NoContent, endRoom(owner, room.roomId))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices/me") { bearerAuth(guest.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/files/${file.id}") { bearerAuth(guest.token) }.status)
        assertEquals(HttpStatusCode.NotFound, endRoom(owner, room.roomId))
        // Expiry rejects the token at once, before any sweep.
        val (_, guest2) = guestOf(owner)
        clock.now += Limits.GUEST_ROOM_TTL_MS
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices/me") { bearerAuth(guest2.token) }.status)
    }

    @Test fun onlyTheOwnerEndsOrSeesTheRoom() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val other = register("Other", DeviceKind.PC)
        val room = openRoom(owner)
        assertEquals(HttpStatusCode.NotFound, endRoom(other, room.roomId))
        assertEquals(HttpStatusCode.NoContent, client.get("/v1/rooms/current") { bearerAuth(other.token) }.status)
        assertNotNull(store.openRoomOf(owner.deviceId, clock.now))
    }

    @Test fun wsClosedOnEnd() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (room, guest) = guestOf(owner)
        client.webSocket("/v1/ws", request = { bearerAuth(guest.token) }) {
            withTimeout(5_000) { while (!deps.hub.isConnected(guest.deviceId)) delay(10) }
            assertEquals(HttpStatusCode.NoContent, endRoom(owner, room.roomId))
            val r = withTimeout(5_000) { incoming.receiveCatching() }
            assertTrue(r.isClosed, "the guest's socket is closed when the room ends")
        }
        assertFalse(deps.hub.isConnected(guest.deviceId))
    }

    @Test fun expiryDuringUploadKeepsOwnerDeliveries() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (_, guest) = guestOf(owner)
        val sent = upload(guest, "a.txt", "done".toByteArray())
        sendFiles(guest, owner.deviceId, listOf(sent.id))
        val bytes = ByteArray(20)
        val open = createUpload(guest, "big.bin", "application/octet-stream", 20)
        putChunk(guest, open.uploadId, bytes, 0, 8)
        val unsent = upload(guest, "never-sent.txt", "x".toByteArray())
        val toGuest = upload(owner, "b.txt", "yo".toByteArray())
        sendFiles(owner, guest.deviceId, listOf(toGuest.id))
        clock.now += Limits.GUEST_ROOM_TTL_MS
        endExpiredRooms(deps)
        assertFalse(java.io.File(deps.files.dir, "${open.uploadId}.part").exists())
        assertFalse(deps.files.file(unsent.id).exists())
        assertFalse(deps.files.file(toGuest.id).exists())
        assertEquals(0, store.rowCount("uploads"))
        assertEquals(1, store.rowCount("files"))
        assertEquals(1, store.rowCount("deliveries"))
        assertEquals(listOf(sent), inbox(owner).single().files)
        assertContentEquals("done".toByteArray(), client.get("/v1/files/${sent.id}") { bearerAuth(owner.token) }.readRawBytes())
    }

    @Test fun sweeperEndsExpiredRoom() = relayTest(roomSweepMs = 50) {
        val owner = register("Desk", DeviceKind.PC)
        val (room, guest) = guestOf(owner)
        withEvents(owner) {
            clock.now += Limits.GUEST_ROOM_TTL_MS
            assertEquals(RelayEvent.RoomEnded(room.roomId), next())
        }
        assertNull(store.device(guest.deviceId))
        assertNull(store.openRoomOf(owner.deviceId, clock.now))
    }

    @Test fun ownerSeesGuestPeerOthersDont() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val (_, guest) = guestOf(pc)
        assertEquals(setOf(phone.deviceId to DeviceKind.PHONE, guest.deviceId to DeviceKind.GUEST), peers(pc).map { it.deviceId to it.kind }.toSet())
        assertEquals(listOf(pc.deviceId), peers(phone).map { it.deviceId })
        // Even a stray link never shows a guest to anyone but its room's owner.
        store.link(phone.deviceId, guest.deviceId)
        assertEquals(listOf(pc.deviceId), peers(phone).map { it.deviceId })
    }

    @Test fun downloadMarksAndNotifiesOwner() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (_, guest) = guestOf(owner)
        val big = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val bigRef = upload(owner, "big.bin", big)
        val small = upload(owner, "small.txt", "hello".toByteArray())
        sendFiles(owner, guest.deviceId, listOf(bigRef.id))
        sendFiles(owner, guest.deviceId, listOf(small.id))
        val bigDelivery = inbox(guest)[0].id
        val smallDelivery = inbox(guest)[1].id
        withEvents(owner) {
            // A download cut off before the last byte is not a download.
            client.prepareGet("/v1/files/${bigRef.id}") { bearerAuth(guest.token); header(HttpHeaders.Range, "bytes=0-") }.execute { r ->
                assertEquals(HttpStatusCode.PartialContent, r.status)
                val buf = ByteArray(1024)
                assertTrue(r.bodyAsChannel().readAvailable(buf, 0, buf.size) > 0)
            }
            assertNull(store.downloadedAt(bigRef.id))
            // The owner's own download is not the guest's.
            client.get("/v1/files/${small.id}") { bearerAuth(owner.token) }.readRawBytes()
            assertNull(store.downloadedAt(small.id))
            // A full GET by the guest is.
            assertContentEquals("hello".toByteArray(), client.get("/v1/files/${small.id}") { bearerAuth(guest.token) }.readRawBytes())
            assertEquals(RelayEvent.Downloaded(smallDelivery, listOf(small.id)), next())
            assertEquals(clock.now, store.downloadedAt(small.id))
            // A resumed download that reaches the last byte counts too.
            val tail = client.get("/v1/files/${bigRef.id}") { bearerAuth(guest.token); header(HttpHeaders.Range, "bytes=${big.size - 10}-") }
            assertEquals(10, tail.readRawBytes().size)
            assertEquals(RelayEvent.Downloaded(bigDelivery, listOf(bigRef.id)), next())
        }
        // The item stays downloadable until the room ends.
        assertEquals(HttpStatusCode.OK, client.get("/v1/files/${small.id}") { bearerAuth(guest.token) }.status)
    }

    @Test fun perRoomCapEnforced() = relayTest(limits = RelayLimits(guestMaxBytes = 10)) {
        val owner = register("Desk", DeviceKind.PC)
        val (_, guest) = guestOf(owner)
        assertEquals(HttpStatusCode.Created, createUploadResponse(guest, "a.bin", "application/octet-stream", 8).status)
        val over = createUploadResponse(guest, "b.bin", "application/octet-stream", 3)
        assertEquals(HttpStatusCode.InsufficientStorage, over.status)
        assertEquals("relay_full", over.error())
        // What the owner sends to the guest counts toward the same room.
        val toGuest = upload(owner, "c.bin", ByteArray(3))
        assertEquals(HttpStatusCode.InsufficientStorage, sendFiles(owner, guest.deviceId, listOf(toGuest.id)).status)
        val fits = upload(owner, "d.bin", ByteArray(2))
        assertEquals(HttpStatusCode.Accepted, sendFiles(owner, guest.deviceId, listOf(fits.id)).status)
        // The owner's own uploads are not capped by the room.
        assertEquals(HttpStatusCode.Created, createUploadResponse(owner, "e.bin", "application/octet-stream", 50).status)
    }

    @Test fun roomCapDefaultsToTheGlobalCap() = relayTest(
        limits = RelayLimits(guestMaxBytes = 1_000),
        files = FileStorage(kotlin.io.path.createTempDirectory("mjolnir-files").toFile(), maxTotalBytes = 10),
    ) {
        val owner = register("Desk", DeviceKind.PC)
        val (room, _) = guestOf(owner)
        assertEquals(10, deps.roomCapBytes())
        assertNotNull(room)
    }

    @Test fun guestCannotJoinSpaces() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val admin = register("Admin", DeviceKind.PC)
        val (_, guest) = guestOf(owner)
        val create = client.post("/v1/spaces") { bearerAuth(guest.token); contentType(ContentType.Application.Json); setBody(CreateSpaceRequest("Mine")) }
        assertEquals(HttpStatusCode.Forbidden, create.status); assertEquals("guest", create.error())
        assertEquals(0, store.rowCount("spaces"))

        val space = client.post("/v1/spaces") { bearerAuth(admin.token); contentType(ContentType.Application.Json); setBody(CreateSpaceRequest("Friends")) }.body<Space>()
        val code = client.post("/v1/spaces/${space.id}/invites") { bearerAuth(admin.token) }.body<PairingCodeResponse>().code
        val claim = client.post("/v1/pairings/claim") {
            bearerAuth(guest.token); contentType(ContentType.Application.Json); setBody(ClaimRequest(code, CodeKind.SPACE_INVITE))
        }
        assertEquals(HttpStatusCode.Forbidden, claim.status); assertEquals("guest", claim.error())

        // Even a claim that slipped past the route is refused at accept.
        val row = assertNotNull(store.claimPairing(code, guest.deviceId, clock.now))
        val accept = client.post("/v1/pairings/${row.id}/accept") { bearerAuth(admin.token) }
        assertEquals(HttpStatusCode.Forbidden, accept.status); assertEquals("guest", accept.error())
        assertEquals(1, store.members(space.id).size)

        // Likewise a pairing code: a guest is never linked to anyone but its owner.
        val pairCode = createCode(admin)
        val pairRow = assertNotNull(store.claimPairing(pairCode, guest.deviceId, clock.now))
        val pairAccept = client.post("/v1/pairings/${pairRow.id}/accept") { bearerAuth(admin.token) }
        assertEquals(HttpStatusCode.Forbidden, pairAccept.status); assertEquals("guest", pairAccept.error())
        assertFalse(store.areLinked(admin.deviceId, guest.deviceId))

        val room = openRoomResponse(guest)
        assertEquals(HttpStatusCode.Forbidden, room.status); assertEquals("guest", room.error())
    }

    @Test fun ticketSingleUseAndExpires() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (room, guest) = guestOf(owner)
        val a = upload(owner, "a.txt", "aaa".toByteArray())
        val b = upload(owner, "b.txt", "bbb".toByteArray())
        sendFiles(owner, guest.deviceId, listOf(a.id))
        sendFiles(owner, guest.deviceId, listOf(b.id))

        val t = ticket(guest, TicketRequest(fileId = a.id))
        val ok = client.get("/v1/files/${a.id}?t=$t")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertContentEquals("aaa".toByteArray(), ok.readRawBytes())
        assertEquals("attachment; filename=\"a.txt\"", ok.headers[HttpHeaders.ContentDisposition])
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/files/${a.id}?t=$t").status, "single use")

        val late = ticket(guest, TicketRequest(fileId = a.id))
        clock.now += Limits.DOWNLOAD_TICKET_TTL_MS + 1
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/files/${a.id}?t=$late").status, "expired")

        val forA = ticket(guest, TicketRequest(fileId = a.id))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/files/${b.id}?t=$forA").status, "bound to its file")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/files/${a.id}?t=$forA").status, "and spent by the attempt")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/inbox?t=${ticket(guest, TicketRequest(fileId = a.id))}").status, "only for downloads")

        // A ticket is only minted for what the device may download.
        val ownersOwn = upload(owner, "c.txt", "c".toByteArray())
        assertEquals(HttpStatusCode.NotFound, ticketResponse(guest, TicketRequest(fileId = ownersOwn.id)).status)
        assertEquals(HttpStatusCode.BadRequest, ticketResponse(guest, TicketRequest()).status)

        val beforeEnd = ticket(guest, TicketRequest(fileId = a.id))
        endRoom(owner, room.roomId)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/files/${a.id}?t=$beforeEnd").status, "void once the room ends")
    }

    @Test fun joinIsRateLimitedPerIp() = relayTest {
        repeat(10) { assertEquals(HttpStatusCode.Gone, joinResponse("guess-$it").status) }
        assertEquals(HttpStatusCode.TooManyRequests, joinResponse("guess-11").status)
    }

    @Test fun unpairingTheGuestEndsTheRoom() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (room, guest) = guestOf(owner)
        val toGuest = upload(owner, "b.txt", "yo".toByteArray())
        sendFiles(owner, guest.deviceId, listOf(toGuest.id))
        withEvents(owner) {
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/peers/${guest.deviceId}") { bearerAuth(owner.token) }.status)
            assertEquals(RelayEvent.RoomEnded(room.roomId), next())
        }
        assertNull(store.room(room.roomId))
        assertNull(store.device(guest.deviceId))
        assertFalse(deps.files.file(toGuest.id).exists())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices/me") { bearerAuth(guest.token) }.status)
    }

    @Test fun ownerSendRacingTheEndCreatesNothing() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (room, guest) = guestOf(owner)
        val file = upload(owner, "b.txt", "yo".toByteArray())
        // The route's checks passed, then the room ended before the delivery was written.
        assertNotNull(store.endRoom(room.roomId))
        val (result, row) = store.createFileDelivery(owner.deviceId, guest.deviceId, listOf(file.id), clock.now)
        assertEquals(AttachResult.NOT_ALLOWED, result)
        assertNull(row)
        assertEquals(0, store.rowCount("deliveries"))
        assertEquals(2L, store.unsentBytes(owner.deviceId, listOf(file.id)), "the file stays unsent, so the unsent-file purge takes it")
    }

    @Test fun joinRacingTheEndIsClosed() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val ended = openRoom(owner)
        // The route found the room, then it ended before the join was written.
        assertNotNull(store.endRoom(ended.roomId))
        assertEquals(Store.JoinResult.Closed, store.joinRoom(ended.roomId, sha256Hex("t1"), clock.now))
        val expired = openRoom(owner)
        assertEquals(Store.JoinResult.Closed, store.joinRoom(expired.roomId, sha256Hex("t2"), clock.now + Limits.GUEST_ROOM_TTL_MS))
        assertTrue(store.joinRoom(expired.roomId, sha256Hex("t3"), clock.now) is Store.JoinResult.Joined)
        assertEquals(Store.JoinResult.InUse, store.joinRoom(expired.roomId, sha256Hex("t4"), clock.now))
        assertEquals(2, store.rowCount("devices"))
    }

    @Test fun uploadPurgedMidCompletionDropsItsBlob() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (room, guest) = guestOf(owner)
        val open = createUpload(guest, "a.bin", "application/octet-stream", 4)
        // Every byte arrived, then the room ended (its rows went) before the upload was recorded as a file.
        store.endRoom(room.roomId)
        assertNull(finishUpload(deps, open.uploadId))
        assertFalse(deps.files.file(open.uploadId).exists())
        assertFalse(java.io.File(deps.files.dir, "${open.uploadId}.part").exists())
        assertEquals(0, store.rowCount("files"))
    }

    @Test fun secretNotInLogs() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val capture = ListAppender<ILoggingEvent>().apply { start() }
        val others = root.iteratorForAppenders().asSequence().toList()
        val level = root.level
        others.forEach(root::detachAppender)
        root.addAppender(capture)
        root.level = Level.TRACE
        val secrets = mutableListOf<String>()
        try {
            relayTest {
                val owner = register("Desk", DeviceKind.PC)
                val room = openRoom(owner)
                secrets += room.secret
                joinResponse(room.secret.dropLast(1))
                join(room.secret)
                joinResponse(room.secret)
                endRoom(owner, room.roomId)
                joinResponse(room.secret)
            }
        } finally {
            root.detachAppender(capture)
            others.forEach(root::addAppender)
            root.level = level
        }
        val secret = secrets.single()
        assertTrue(capture.list.isNotEmpty(), "the capture works")
        val leaks = capture.list.filter { e ->
            e.formattedMessage.orEmpty().contains(secret) || e.throwableProxy?.message.orEmpty().contains(secret) ||
                e.argumentArray.orEmpty().any { it.toString().contains(secret) }
        }
        assertEquals(emptyList(), leaks.map { it.formattedMessage })
    }
}
