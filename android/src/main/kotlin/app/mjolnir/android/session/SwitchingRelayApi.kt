package app.mjolnir.android.session

import app.mjolnir.client.RelayApi
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.FileRef
import java.io.File

/** Forwards every call to whatever [current] returns at that moment, so the delegate can be swapped. Exceptions pass through unchanged. */
class SwitchingRelayApi(private val current: () -> RelayApi) : RelayApi {
    override suspend fun register(name: String, kind: app.mjolnir.protocol.DeviceKind) = current().register(name, kind)
    override suspend fun me() = current().me()
    override suspend fun rename(name: String) = current().rename(name)
    override suspend fun setPushToken(pushToken: String) = current().setPushToken(pushToken)
    override suspend fun createPairingCode() = current().createPairingCode()
    override suspend fun claimPairingCode(code: String, expect: app.mjolnir.protocol.CodeKind) = current().claimPairingCode(code, expect)
    override suspend fun pairingStatus(pairingId: String) = current().pairingStatus(pairingId)
    override suspend fun acceptPairing(pairingId: String) = current().acceptPairing(pairingId)
    override suspend fun rejectPairing(pairingId: String) = current().rejectPairing(pairingId)
    override suspend fun peers() = current().peers()
    override suspend fun unpair(deviceId: String) = current().unpair(deviceId)
    override suspend fun send(to: String, tabs: List<Tab>, fileIds: List<String>, spaceId: String?) = current().send(to, tabs, fileIds, spaceId)
    override suspend fun createSpace(name: String) = current().createSpace(name)
    override suspend fun spaces() = current().spaces()
    override suspend fun renameSpace(id: String, name: String) = current().renameSpace(id, name)
    override suspend fun deleteSpace(id: String) = current().deleteSpace(id)
    override suspend fun createInvite(spaceId: String) = current().createInvite(spaceId)
    override suspend fun openRoom() = current().openRoom()
    override suspend fun currentRoom() = current().currentRoom()
    override suspend fun endRoom(id: String) = current().endRoom(id)
    override suspend fun removeMember(spaceId: String, deviceId: String) = current().removeMember(spaceId, deviceId)
    override suspend fun transferAdmin(spaceId: String, deviceId: String) = current().transferAdmin(spaceId, deviceId)
    override suspend fun takeOverAdmin(spaceId: String) = current().takeOverAdmin(spaceId)
    override suspend fun inbox() = current().inbox()
    override suspend fun ack(deliveryId: String) = current().ack(deliveryId)
    override suspend fun createUpload(relativePath: String, mimeType: String, sizeBytes: Long) =
        current().createUpload(relativePath, mimeType, sizeBytes)
    override suspend fun uploadStatus(uploadId: String) = current().uploadStatus(uploadId)
    override suspend fun uploadChunk(uploadId: String, start: Long, total: Long, bytes: ByteArray, length: Int) =
        current().uploadChunk(uploadId, start, total, bytes, length)
    override suspend fun downloadFile(ref: FileRef, target: File, onProgress: (Long) -> Unit) =
        current().downloadFile(ref, target, onProgress)
    override fun events() = current().events()
}
