package app.mjolnir.client

import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RegisterResponse
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.RoomResponse
import app.mjolnir.protocol.RoomStatus
import app.mjolnir.protocol.SendRequest
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.SendResponse
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.UploadStatus
import app.mjolnir.protocol.safeFileName
import app.mjolnir.protocol.safeRelativePath
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeRelayApi : RelayApi {
    var registerResult = RegisterResponse("dev-1", "tok-1")
    val registerCalls = mutableListOf<Pair<String, DeviceKind>>()
    var me = Peer("dev-1", "Me", DeviceKind.PC)
    val peers = mutableListOf<Peer>()
    val unpaired = mutableListOf<String>()
    val inbox = mutableListOf<Delivery>()
    val acked = mutableListOf<String>()
    val sent = mutableListOf<SendRequest>()
    var pairingCodeResult = PairingCodeResponse("ABC123", Long.MAX_VALUE)
    var onSend: suspend (SendRequest) -> SendResponse = { SendResponse("del-${sent.size}") }
    var onClaim: suspend (String) -> ClaimResponse = { ClaimResponse("pair-1", Peer("owner", "Owner", DeviceKind.PC)) }
    val claimExpects = mutableListOf<CodeKind>()
    val statuses = ArrayDeque<PairingStatus>()
    val accepted = mutableListOf<String>()
    val rejected = mutableListOf<String>()
    val pushTokens = mutableListOf<String>()
    val events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 64)
    var failAllWith: RelayException? = null

    /** Finished files by id; [downloadFile] serves from here. */
    val blobs: MutableMap<String, ByteArray> = mutableMapOf()
    /** Open upload sessions by upload id. */
    val sessions: MutableMap<String, ByteArrayOutputStream> = mutableMapOf()
    /** Completed uploads, in order. */
    val uploads: MutableList<FileRef> = mutableListOf()
    /** Thrown by every [uploadChunk] while set. */
    var failUploadsWith: RelayException? = null
    private val sessionMeta = mutableMapOf<String, Triple<String, String, Long>>()
    private var nextUpload = 0

    /** Stores [bytes] as a finished file and returns its ref with the correct size and SHA-256. */
    fun putBlob(id: String, relativePath: String, bytes: ByteArray): FileRef {
        val path = safeRelativePath(relativePath)
        blobs[id] = bytes
        return FileRef(id, safeFileName(path), bytes.size.toLong(), "application/octet-stream", sha256(bytes), path)
    }

    /** Forgets finished file or open session [id], as the relay does after its unsent-file / session TTL. */
    fun expire(id: String) {
        blobs.remove(id); uploads.removeAll { it.id == id }; sessions.remove(id)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun complete(id: String): FileRef {
        val (path, mime, _) = sessionMeta.getValue(id)
        val bytes = sessions.remove(id)!!.toByteArray()
        val ref = putBlob(id, path, bytes).copy(mimeType = mime)
        uploads += ref
        return ref
    }

    private fun check() { failAllWith?.let { throw it } }

    override suspend fun register(name: String, kind: DeviceKind): RegisterResponse {
        check(); registerCalls += name to kind; return registerResult
    }
    override suspend fun me(): Peer { check(); return me }
    override suspend fun rename(name: String): Peer { check(); me = me.copy(name = name); return me }
    override suspend fun setPushToken(pushToken: String) { check(); pushTokens += pushToken }
    override suspend fun createPairingCode(): PairingCodeResponse { check(); return pairingCodeResult }
    override suspend fun claimPairingCode(code: String, expect: CodeKind): ClaimResponse { check(); claimExpects += expect; return onClaim(code) }
    override suspend fun pairingStatus(pairingId: String): PairingStatus {
        check()
        if (statuses.isEmpty()) return PairingStatus(PairingState.PENDING)
        return if (statuses.size > 1) statuses.removeFirst() else statuses.first()
    }
    override suspend fun acceptPairing(pairingId: String) { check(); accepted += pairingId }
    override suspend fun rejectPairing(pairingId: String) { check(); rejected += pairingId }
    override suspend fun peers(): List<Peer> { check(); return peers.toList() }
    override suspend fun unpair(deviceId: String) { check(); unpaired += deviceId; peers.removeAll { it.deviceId == deviceId } }
    override suspend fun send(to: String, tabs: List<Tab>, fileIds: List<String>, spaceId: String?): SendResponse {
        check()
        val request = SendRequest(to, tabs, fileIds, spaceId)
        sent += request
        return onSend(request)
    }
    /** In-memory spaces, each with [me] as a member unless removed; the fake enforces the relay's rules for them. */
    val spaceList = mutableListOf<Space>()
    private var nextSpace = 0

    /** Puts [peer] into space [spaceId] (test setup, as if they had joined). */
    fun addMember(spaceId: String, peer: Peer) = updateSpace(spaceId) { it.copy(members = it.members + peer) }

    /** Test setup: a space [id] administered by [admin], where [me] is a plain member. */
    fun asMemberOf(id: String, admin: Peer, adminSilent: Boolean = false, name: String = "Space $id") {
        spaceList += Space(id, name, admin.deviceId, listOf(admin, me), adminSilent)
    }

    private fun err(kind: RelayErrorKind, code: String) = RelayException(kind, code)

    private fun updateSpace(id: String, change: (Space) -> Space): Space {
        val index = spaceList.indexOfFirst { it.id == id }
        require(index >= 0) { "no such fake space $id" }
        return change(spaceList[index]).also { spaceList[index] = it }
    }

    /** The space if [me] is a member (else NOT_FOUND). */
    private fun memberSpace(id: String): Space =
        spaceList.find { it.id == id && it.members.any { m -> m.deviceId == me.deviceId } } ?: throw err(RelayErrorKind.NOT_FOUND, "not_found")

    private fun adminSpace(id: String): Space =
        memberSpace(id).also { if (it.adminId != me.deviceId) throw err(RelayErrorKind.NOT_PAIRED, "not_admin") }

    private fun validName(raw: String): String =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= Limits.MAX_SPACE_NAME_LENGTH && it.none(Char::isISOControl) }
            ?: throw err(RelayErrorKind.INVALID, "invalid_name")

    override suspend fun createSpace(name: String): Space {
        check()
        val valid = validName(name)
        return Space("space-${++nextSpace}", valid, me.deviceId, listOf(me), false).also { spaceList += it }
    }

    override suspend fun spaces(): List<Space> { check(); return spaceList.filter { s -> s.members.any { it.deviceId == me.deviceId } } }

    override suspend fun renameSpace(id: String, name: String): Space {
        check(); adminSpace(id)
        val valid = validName(name)
        return updateSpace(id) { it.copy(name = valid) }
    }

    override suspend fun deleteSpace(id: String) { check(); adminSpace(id); spaceList.removeAll { it.id == id } }

    override suspend fun createInvite(spaceId: String): PairingCodeResponse { check(); adminSpace(spaceId); return pairingCodeResult }

    override suspend fun removeMember(spaceId: String, deviceId: String) {
        check()
        val space = memberSpace(spaceId)
        if (deviceId != me.deviceId && space.adminId != me.deviceId) throw err(RelayErrorKind.NOT_PAIRED, "not_admin")
        if (space.members.none { it.deviceId == deviceId }) throw err(RelayErrorKind.NOT_FOUND, "not_found")
        if (deviceId == space.adminId) throw err(RelayErrorKind.ALREADY_SENT, "admin_must_transfer")
        updateSpace(spaceId) { it.copy(members = it.members.filter { m -> m.deviceId != deviceId }) }
    }

    override suspend fun transferAdmin(spaceId: String, deviceId: String): Space {
        check(); val space = adminSpace(spaceId)
        if (space.members.none { it.deviceId == deviceId }) throw err(RelayErrorKind.NOT_FOUND, "not_found")
        return updateSpace(spaceId) { it.copy(adminId = deviceId, adminSilent = if (deviceId == me.deviceId) false else it.adminSilent) }
    }

    override suspend fun takeOverAdmin(spaceId: String): Space {
        check(); val space = memberSpace(spaceId)
        if (space.adminId == me.deviceId || !space.adminSilent) {
            throw err(RelayErrorKind.ALREADY_SENT, "admin_active")
        }
        return updateSpace(spaceId) { it.copy(adminId = me.deviceId, adminSilent = false) }
    }

    /** The open guest room, if any (test setup may assign it directly). */
    var room: RoomResponse? = null
    /** The guest in [room]; shown by [currentRoom]. */
    var roomGuest: Peer? = null
    /** The relay's clock as [currentRoom] reports it. */
    var serverNowEpochMs = 0L
    val endedRooms = mutableListOf<String>()
    private var nextRoom = 0

    override suspend fun openRoom(): RoomResponse {
        check()
        if (room != null) throw err(RelayErrorKind.ALREADY_SENT, "room_open")
        return RoomResponse("room-${++nextRoom}", "secret-$nextRoom", serverNowEpochMs + Limits.GUEST_ROOM_TTL_MS).also { room = it }
    }

    /** While set, [currentRoom] answers with the room as it was when called, but only once this completes. */
    var currentRoomGate: CompletableDeferred<Unit>? = null

    override suspend fun currentRoom(): RoomStatus? {
        check()
        val r = room
        val status = r?.let { RoomStatus(it.roomId, it.expiresAtEpochMs, serverNowEpochMs, roomGuest) }
        currentRoomGate?.await()
        return status
    }

    override suspend fun endRoom(id: String) {
        check()
        if (room?.roomId != id) throw err(RelayErrorKind.NOT_FOUND, "not_found")
        endedRooms += id; room = null; roomGuest = null
    }

    override suspend fun inbox(): List<Delivery> { check(); return inbox.toList() }
    override suspend fun ack(deliveryId: String) { check(); acked += deliveryId; inbox.removeAll { it.id == deliveryId } }
    override fun events(): Flow<RelayEvent> = events

    override suspend fun createUpload(relativePath: String, mimeType: String, sizeBytes: Long): UploadStatus {
        check()
        val id = "up-${++nextUpload}"
        sessions[id] = ByteArrayOutputStream()
        sessionMeta[id] = Triple(relativePath, mimeType, sizeBytes)
        return UploadStatus(id, 0, if (sizeBytes == 0L) complete(id) else null)
    }

    override suspend fun uploadStatus(uploadId: String): UploadStatus {
        check()
        sessions[uploadId]?.let { return UploadStatus(uploadId, it.size().toLong()) }
        val ref = uploads.find { it.id == uploadId }?.takeIf { it.id in blobs }
            ?: throw RelayException(RelayErrorKind.NOT_FOUND, "not_found")
        return UploadStatus(uploadId, ref.sizeBytes, ref)
    }

    override suspend fun uploadChunk(uploadId: String, start: Long, total: Long, bytes: ByteArray, length: Int): UploadStatus {
        check()
        failUploadsWith?.let { throw it }
        val session = sessions[uploadId] ?: return uploadStatus(uploadId)
        if (start != session.size().toLong()) return UploadStatus(uploadId, session.size().toLong())
        session.write(bytes, 0, length)
        val offset = session.size().toLong()
        return UploadStatus(uploadId, offset, if (offset >= sessionMeta.getValue(uploadId).third) complete(uploadId) else null)
    }

    override suspend fun downloadFile(ref: FileRef, target: File, onProgress: (Long) -> Unit) {
        check()
        val bytes = blobs[ref.id] ?: throw RelayException(RelayErrorKind.NOT_FOUND, "not_found")
        target.absoluteFile.parentFile?.mkdirs()
        val have = if (target.isFile && target.length() <= ref.sizeBytes) target.length().toInt() else 0
        onProgress(have.toLong())
        if (have == 0) target.writeBytes(ByteArray(0))
        if (have < bytes.size) target.appendBytes(bytes.copyOfRange(minOf(have, bytes.size), bytes.size))
        onProgress(target.length())
        if (target.length() != ref.sizeBytes || sha256(target.readBytes()) != ref.sha256) {
            target.delete()
            throw RelayException(RelayErrorKind.INVALID, "hash mismatch")
        }
    }
}

fun delivery(
    id: String,
    from: Peer = Peer("ph", "Pixel 8", DeviceKind.PHONE),
    tabs: List<Tab> = listOf(Tab("https://example.com/$id")),
): Delivery = Delivery(id, from, tabs, 0)

class MemorySeen : SeenDeliveries {
    private val ids = mutableSetOf<String>()
    override fun contains(id: String) = id in ids
    override fun add(id: String) { ids += id }
}
