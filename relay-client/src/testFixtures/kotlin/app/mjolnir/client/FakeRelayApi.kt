package app.mjolnir.client

import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RegisterResponse
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.SendRequest
import app.mjolnir.protocol.SendResponse
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.UploadStatus
import app.mjolnir.protocol.safeFileName
import app.mjolnir.protocol.safeRelativePath
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
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
    override suspend fun claimPairingCode(code: String): ClaimResponse { check(); return onClaim(code) }
    override suspend fun pairingStatus(pairingId: String): PairingStatus {
        check()
        if (statuses.isEmpty()) return PairingStatus(PairingState.PENDING)
        return if (statuses.size > 1) statuses.removeFirst() else statuses.first()
    }
    override suspend fun acceptPairing(pairingId: String) { check(); accepted += pairingId }
    override suspend fun rejectPairing(pairingId: String) { check(); rejected += pairingId }
    override suspend fun peers(): List<Peer> { check(); return peers.toList() }
    override suspend fun unpair(deviceId: String) { check(); unpaired += deviceId; peers.removeAll { it.deviceId == deviceId } }
    override suspend fun send(to: String, tabs: List<Tab>, fileIds: List<String>): SendResponse {
        check()
        val request = SendRequest(to, tabs, fileIds)
        sent += request
        return onSend(request)
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
        val ref = uploads.find { it.id == uploadId } ?: throw RelayException(RelayErrorKind.NOT_FOUND, "not_found")
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
