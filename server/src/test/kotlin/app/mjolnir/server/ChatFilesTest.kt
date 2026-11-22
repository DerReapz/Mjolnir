package app.mjolnir.server

import app.mjolnir.protocol.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ChatFilesTest {
    private class Cast(
        val admin: RegisterResponse, val owner: RegisterResponse, val member: RegisterResponse,
        val outsider: RegisterResponse, val space: Space, val chat: ChatSummary,
    )

    /** Space with admin A (not in the chat), chat owner B, chat member C, and D: a space member outside the chat. */
    private suspend fun RelayTestScope.cast(): Cast {
        val a = register("A"); val b = register("B"); val c = register("C"); val d = register("D")
        val space = client.post("/v1/spaces") {
            bearerAuth(a.token); contentType(ContentType.Application.Json); setBody(CreateSpaceRequest("Friends"))
        }.body<Space>()
        for (j in listOf(b, c, d)) {
            val code = client.post("/v1/spaces/${space.id}/invites") { bearerAuth(a.token) }.body<PairingCodeResponse>().code
            val claim = client.post("/v1/pairings/claim") {
                bearerAuth(j.token); contentType(ContentType.Application.Json); setBody(ClaimRequest(code, CodeKind.SPACE_INVITE))
            }.body<ClaimResponse>()
            check(client.post("/v1/pairings/${claim.pairingId}/accept") { bearerAuth(a.token) }.status == HttpStatusCode.NoContent)
        }
        val chat = client.post("/v1/spaces/${space.id}/chats") {
            bearerAuth(b.token); contentType(ContentType.Application.Json); setBody(CreateChatRequest("Trip"))
        }.body<ChatSummary>()
        val inv = client.post("/v1/chats/${chat.id}/invites") {
            bearerAuth(b.token); contentType(ContentType.Application.Json); setBody(InviteRequest(c.deviceId))
        }.body<ChatInvite>()
        check(client.post("/v1/chat-invites/${inv.id}/join") { bearerAuth(c.token) }.status == HttpStatusCode.OK)
        return Cast(a, b, c, d, space, chat)
    }

    private suspend fun RelayTestScope.postWith(d: RegisterResponse, chatId: String, text: String?, vararg att: Attachment): HttpResponse =
        client.post("/v1/chats/$chatId/messages") {
            bearerAuth(d.token); contentType(ContentType.Application.Json); setBody(PostMessageRequest(text, attachments = att.toList()))
        }

    private suspend fun RelayTestScope.postOk(d: RegisterResponse, chatId: String, text: String?, vararg att: Attachment): ChatMessage {
        val r = postWith(d, chatId, text, *att)
        check(r.status == HttpStatusCode.Created) { "post ${r.status}" }
        return r.body()
    }

    /** Uploads [bytes] and a thumbnail, returns the attachment describing them. */
    private suspend fun RelayTestScope.photo(d: RegisterResponse, name: String = "p.jpg", bytes: ByteArray = ByteArray(100) { 7 }): Attachment {
        val f = upload(d, name, bytes, "image/jpeg")
        val t = upload(d, "t-$name", ByteArray(10) { 1 }, "image/jpeg")
        return Attachment(f.id, AttachmentKind.IMAGE, name, bytes.size.toLong(), "image/jpeg", 640, 480, thumbFileId = t.id)
    }

    private suspend fun RelayTestScope.fetch(d: RegisterResponse, id: String): HttpResponse =
        client.get("/v1/files/$id") { bearerAuth(d.token) }

    private fun RelayTestScope.blobExists(id: String) = deps.files.file(id).isFile

    private suspend fun RelayTestScope.sync(d: RegisterResponse, chatId: String): ChatSync =
        client.get("/v1/chats/$chatId/sync?sinceRev=0") { bearerAuth(d.token) }.body()

    @Test fun attachmentsStampedAndReturnedInSync() = relayTest {
        val k = cast()
        val att = photo(k.owner).copy(sizeBytes = 1, mime = "text/evil", relativePath = "../trip/a.jpg")
        val sent = postOk(k.owner, k.chat.id, null, att)
        val a = sent.attachments.single()
        assertEquals(100L, a.sizeBytes) // size and mime come from the stored file, not the client
        assertEquals("image/jpeg", a.mime)
        assertEquals("trip/a.jpg", a.relativePath)
        assertEquals(att.thumbFileId, a.thumbFileId)
        assertEquals(640, a.width)
        assertNull(sent.text)
        val synced = sync(k.member, k.chat.id).messages.single { it.id == sent.id }
        assertEquals(sent.attachments, synced.attachments)
        assertEquals(sent.attachments, sync(k.admin, k.chat.id).messages.single { it.id == sent.id }.attachments)
    }

    @Test fun chatFilesSurvivePurges() = relayTest {
        val k = cast()
        val att = photo(k.owner)
        postOk(k.owner, k.chat.id, "look", att)
        clock.now += 8 * 86_400_000L
        purge(deps)
        assertEquals(HttpStatusCode.OK, fetch(k.member, att.fileId).status)
        assertEquals(HttpStatusCode.OK, fetch(k.member, att.thumbFileId!!).status)
        assertTrue(blobExists(att.fileId))
        // an unsent upload still goes
        val stray = upload(k.owner, "stray.bin", ByteArray(5))
        clock.now += 2 * 3_600_000L
        purge(deps)
        assertFalse(blobExists(stray.id))
    }

    @Test fun membersAndAdminCanDownload() = relayTest {
        val k = cast()
        val bytes = ByteArray(100) { it.toByte() }
        val att = photo(k.owner, bytes = bytes)
        postOk(k.owner, k.chat.id, null, att)
        for (d in listOf(k.owner, k.member, k.admin)) {
            val r = fetch(d, att.fileId)
            assertEquals(HttpStatusCode.OK, r.status)
            assertContentEquals(bytes, r.body<ByteArray>())
            assertEquals(HttpStatusCode.OK, fetch(d, att.thumbFileId!!).status)
        }
    }

    @Test fun nonMemberCannotDownloadChatFile() = relayTest {
        val k = cast()
        val att = photo(k.owner)
        // before it is posted only the owner may fetch it
        assertEquals(HttpStatusCode.NotFound, fetch(k.member, att.fileId).status)
        postOk(k.owner, k.chat.id, null, att)
        val stranger = register("Stranger")
        for (d in listOf(k.outsider, stranger)) {
            assertEquals(HttpStatusCode.NotFound, fetch(d, att.fileId).status)
            assertEquals(HttpStatusCode.NotFound, fetch(d, att.thumbFileId!!).status)
        }
        // a member who leaves loses access too
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/chats/${k.chat.id}/members/${k.member.deviceId}") { bearerAuth(k.member.token) }.status)
        assertEquals(HttpStatusCode.NotFound, fetch(k.member, att.fileId).status)
    }

    @Test fun fileGoneAfterMessageDelete() = relayTest {
        val k = cast()
        val att = photo(k.owner)
        val keep = photo(k.owner, "keep.jpg")
        val msg = postOk(k.owner, k.chat.id, "x", att)
        postOk(k.owner, k.chat.id, "y", keep)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/chats/${k.chat.id}/messages/${msg.id}") { bearerAuth(k.owner.token) }.status)
        for (id in listOf(att.fileId, att.thumbFileId!!)) {
            assertFalse(blobExists(id))
            for (d in listOf(k.owner, k.member, k.admin)) assertEquals(HttpStatusCode.NotFound, fetch(d, id).status)
        }
        assertTrue(sync(k.member, k.chat.id).messages.single { it.id == msg.id }.attachments.isEmpty())
        assertTrue(blobExists(keep.fileId))
        assertEquals(HttpStatusCode.OK, fetch(k.member, keep.fileId).status)
        // the freed ids are not reusable
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, null, att).status)
    }

    @Test fun chatDeleteFreesBlobs() = relayTest {
        val k = cast()
        val att = photo(k.owner)
        val other = photo(k.member, "o.jpg")
        postOk(k.owner, k.chat.id, null, att)
        postOk(k.member, k.chat.id, null, other)
        val ids = listOf(att.fileId, att.thumbFileId!!, other.fileId, other.thumbFileId!!)
        assertTrue(ids.all { blobExists(it) })
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/chats/${k.chat.id}") { bearerAuth(k.owner.token) }.status)
        ids.forEach { assertFalse(blobExists(it), it) }
        ids.forEach { assertEquals(HttpStatusCode.NotFound, fetch(k.admin, it).status) }
    }

    @Test fun lastMemberRemovedFreesBlobs() = relayTest {
        val k = cast()
        val solo = client.post("/v1/spaces/${k.space.id}/chats") {
            bearerAuth(k.outsider.token); contentType(ContentType.Application.Json); setBody(CreateChatRequest("Solo"))
        }.body<ChatSummary>()
        val att = photo(k.outsider)
        postOk(k.outsider, solo.id, null, att)
        // only the admin can remove the owner; the last member going deletes the chat
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/chats/${solo.id}/members/${k.outsider.deviceId}") { bearerAuth(k.admin.token) }.status)
        assertNull(deps.store.chat(solo.id))
        assertFalse(blobExists(att.fileId))
        assertFalse(blobExists(att.thumbFileId!!))
    }

    @Test fun spaceMemberRemovalFreesBlobsOfDeletedChat() = relayTest {
        val k = cast()
        val solo = client.post("/v1/spaces/${k.space.id}/chats") {
            bearerAuth(k.outsider.token); contentType(ContentType.Application.Json); setBody(CreateChatRequest("Solo"))
        }.body<ChatSummary>()
        val soloAtt = photo(k.outsider)
        postOk(k.outsider, solo.id, null, soloAtt)
        val shared = photo(k.owner)
        postOk(k.owner, k.chat.id, null, shared)
        // the admin removes D from the space: D's chat becomes empty and is deleted; the shared chat is untouched
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${k.space.id}/members/${k.outsider.deviceId}") { bearerAuth(k.admin.token) }.status)
        assertFalse(blobExists(soloAtt.fileId))
        assertFalse(blobExists(soloAtt.thumbFileId!!))
        assertTrue(blobExists(shared.fileId))
        // C leaving the space leaves the chat (still has B), so its files stay
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${k.space.id}/members/${k.member.deviceId}") { bearerAuth(k.member.token) }.status)
        assertTrue(blobExists(shared.fileId))
    }

    @Test fun spaceDeleteFreesBlobs() = relayTest {
        val k = cast()
        val att = photo(k.owner)
        val att2 = photo(k.member, "m.jpg")
        postOk(k.owner, k.chat.id, null, att)
        postOk(k.member, k.chat.id, "t", att2)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/spaces/${k.space.id}") { bearerAuth(k.admin.token) }.status)
        for (id in listOf(att.fileId, att.thumbFileId!!, att2.fileId, att2.thumbFileId!!)) {
            assertFalse(blobExists(id), id)
            assertEquals(HttpStatusCode.NotFound, fetch(k.owner, id).status)
        }
    }

    @Test fun cannotAttachOthersOrUsedFiles() = relayTest {
        val k = cast()
        val mine = photo(k.owner)
        val theirs = photo(k.member, "t.jpg")
        // someone else's file or thumbnail
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, "x", theirs).status)
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, "x", mine.copy(thumbFileId = theirs.thumbFileId)).status)
        // unknown id
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, "x", mine.copy(fileId = "nope")).status)
        // one id twice (as two attachments, or as file and thumbnail)
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, "x", mine, mine).status)
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, "x", mine.copy(thumbFileId = mine.fileId)).status)
        // a file already sent to a device cannot go into a chat
        val sentFile = upload(k.owner, "s.bin", ByteArray(3))
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, "x", Attachment(sentFile.id, AttachmentKind.FILE, "s.bin", 3, "application/octet-stream").also {
            deps.store.attachFiles(listOf(sentFile.id), k.owner.deviceId, k.member.deviceId, "delivery-1")
        }).status)
        // nothing above stuck: the good message still posts, and its files are then used up
        assertTrue(sync(k.owner, k.chat.id).messages.none { it.attachments.isNotEmpty() })
        postOk(k.owner, k.chat.id, "x", mine)
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, "again", mine).status)
        // a chat file cannot be sent as a delivery either
        assertEquals(AttachResult.INVALID, deps.store.attachFiles(listOf(mine.fileId), k.owner.deviceId, k.member.deviceId, "delivery-2"))
        // empty messages and too many attachments
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, "  ").status)
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, null).status)
        val many = (1..Limits.MAX_ATTACHMENTS_PER_MESSAGE + 1).map { Attachment("f$it", AttachmentKind.FILE, "f", 1, "x/y") }
        assertEquals(HttpStatusCode.BadRequest, postWith(k.owner, k.chat.id, "x", *many.toTypedArray()).status)
    }

    @Test fun outOfRangeMediaMetadataIsDropped() = relayTest {
        val k = cast()
        suspend fun video(name: String): Attachment {
            val f = upload(k.owner, name, ByteArray(50), "video/mp4")
            return Attachment(f.id, AttachmentKind.VIDEO, name, 50, "video/mp4")
        }
        // Out of range (ruling CV-R1): the message still posts, with the bad values left out.
        for (bad in listOf<(Attachment) -> Attachment>(
            { it.copy(durationMs = 0) }, { it.copy(durationMs = -5) }, { it.copy(durationMs = Limits.MAX_MEDIA_DURATION_MS + 1) },
        )) {
            val a = postOk(k.owner, k.chat.id, null, bad(video("bad.mp4")).copy(width = 640, height = 360)).attachments.single()
            assertEquals(Triple(null, 640, 360), Triple(a.durationMs, a.width, a.height))
        }
        for (bad in listOf<(Attachment) -> Attachment>(
            { it.copy(width = 0, height = 100) }, { it.copy(width = 100, height = -1) }, { it.copy(width = Limits.MAX_MEDIA_SIDE_PX + 1, height = 100) },
            { it.copy(width = 100, height = null) },
        )) {
            val a = postOk(k.owner, k.chat.id, null, bad(video("bad.mp4")).copy(durationMs = 5_000)).attachments.single()
            assertEquals(Triple(5_000L, null, null), Triple(a.durationMs, a.width, a.height)) // a size is both sides or none
        }
        // An old client's panorama over 16384 px posts as a photo without a size.
        val pano = photo(k.owner, "pano.jpg").copy(width = 20_000, height = 3_000)
        val p = postOk(k.owner, k.chat.id, null, pano).attachments.single()
        assertEquals(null to null, p.width to p.height)
        assertNotNull(p.thumbFileId)
        // In bounds, at the caps, or absent (a video whose capture failed): stored as sent.
        val ok = postOk(k.owner, k.chat.id, null, video("a.mp4").copy(durationMs = 42_000, width = 1080, height = 1920)).attachments.single()
        assertEquals(Triple(42_000L, 1080, 1920), Triple(ok.durationMs, ok.width, ok.height))
        val plain = postOk(k.owner, k.chat.id, null, video("b.mp4")).attachments.single()
        assertEquals(Triple(null, null, null), Triple(plain.durationMs, plain.width, plain.height))
        val caps = postOk(k.owner, k.chat.id, null, video("c.mp4").copy(
            durationMs = Limits.MAX_MEDIA_DURATION_MS, width = Limits.MAX_MEDIA_SIDE_PX, height = Limits.MAX_MEDIA_SIDE_PX,
        )).attachments.single()
        assertEquals(Triple(Limits.MAX_MEDIA_DURATION_MS, Limits.MAX_MEDIA_SIDE_PX, Limits.MAX_MEDIA_SIDE_PX), Triple(caps.durationMs, caps.width, caps.height))
        // What sync serves is what was stored.
        assertTrue(sync(k.member, k.chat.id).messages.flatMap { it.attachments }.none {
            (it.width ?: 1) !in 1..Limits.MAX_MEDIA_SIDE_PX || (it.durationMs ?: 1) !in 1..Limits.MAX_MEDIA_DURATION_MS
        })
    }

    @Test fun twentyAttachmentsAccepted() = relayTest {
        val k = cast()
        val atts = (1..Limits.MAX_ATTACHMENTS_PER_MESSAGE).map {
            val f = upload(k.owner, "f$it.txt", ByteArray(2))
            Attachment(f.id, AttachmentKind.FILE, "f$it.txt", 2, "application/octet-stream")
        }
        assertEquals(20, postOk(k.owner, k.chat.id, null, *atts.toTypedArray()).attachments.size)
    }

    @Test fun perFileChatLimit() = relayTest(limits = RelayLimits(maxChatFileBytes = 10)) {
        val k = cast()
        val big = upload(k.owner, "big.bin", ByteArray(11)) // fits the upload limit, not the chat one
        val small = upload(k.owner, "small.bin", ByteArray(10))
        val r = postWith(k.owner, k.chat.id, "x", Attachment(big.id, AttachmentKind.FILE, "big.bin", 11, "application/octet-stream"))
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertEquals("too_large", r.body<ErrorResponse>().error)
        assertTrue(sync(k.owner, k.chat.id).messages.none { it.attachments.isNotEmpty() })
        assertEquals(HttpStatusCode.Created, postWith(k.owner, k.chat.id, "x", Attachment(small.id, AttachmentKind.FILE, "small.bin", 10, "application/octet-stream")).status)
    }

    @Test fun chatLimitAbovePerFileLimitOnlyAppliesToChat() {
        val files = FileStorage(createTempDirectory("mjolnir-files").toFile(), maxFileBytes = 10, maxUploadBytes = 20)
        relayTest(files = files, limits = RelayLimits(maxChatFileBytes = 20, registrationsPerHourPerIp = 100)) {
            val k = cast()
            val big = upload(k.owner, "big.bin", ByteArray(20))
            assertEquals(HttpStatusCode.PayloadTooLarge, createUploadResponse(k.owner, "huge.bin", "application/octet-stream", 21).status)
            // as a delivery the same file is over the per-file limit
            val (pc, phone) = pairedPcAndPhone()
            val bigForSend = upload(pc, "big2.bin", ByteArray(20))
            assertEquals(HttpStatusCode.BadRequest, sendFiles(pc, phone.deviceId, listOf(bigForSend.id)).status)
            assertEquals(AttachResult.INVALID, deps.store.attachFiles(listOf(big.id), k.owner.deviceId, k.member.deviceId, "d", files.maxFileBytes))
            // a guest room keeps the per-file limit
            val (_, guest) = guestOf(pc)
            assertEquals(HttpStatusCode.PayloadTooLarge, createUploadResponse(guest, "g.bin", "application/octet-stream", 11).status)
            // in a chat it posts
            val a = postOk(k.owner, k.chat.id, null, Attachment(big.id, AttachmentKind.FILE, "big.bin", 20, "application/octet-stream")).attachments.single()
            assertEquals(20L, a.sizeBytes)
            assertEquals(HttpStatusCode.OK, fetch(k.member, big.id).status)
        }
    }

    @Test fun storageFullIs507() {
        val files = FileStorage(createTempDirectory("mjolnir-files").toFile(), maxTotalBytes = 150)
        relayTest(files = files) {
            val k = cast()
            val att = photo(k.owner) // 110 bytes
            postOk(k.owner, k.chat.id, null, att)
            val r = createUploadResponse(k.member, "more.bin", "application/octet-stream", 100)
            assertEquals(HttpStatusCode.InsufficientStorage, r.status)
            assertEquals("relay_full", r.body<ErrorResponse>().error)
            // deleting the message frees the space again
            val msgId = sync(k.owner, k.chat.id).messages.single { it.attachments.isNotEmpty() }.id
            client.delete("/v1/chats/${k.chat.id}/messages/$msgId") { bearerAuth(k.owner.token) }
            assertEquals(HttpStatusCode.Created, createUploadResponse(k.member, "more.bin", "application/octet-stream", 100).status)
        }
    }
}
