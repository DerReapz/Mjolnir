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
    override suspend fun chats(spaceId: String) = current().chats(spaceId)
    override suspend fun createChat(spaceId: String, name: String) = current().createChat(spaceId, name)
    override suspend fun renameChat(id: String, name: String) = current().renameChat(id, name)
    override suspend fun deleteChat(id: String) = current().deleteChat(id)
    override suspend fun inviteToChat(chatId: String, deviceId: String) = current().inviteToChat(chatId, deviceId)
    override suspend fun chatInvites() = current().chatInvites()
    override suspend fun approveInvite(id: String) = current().approveInvite(id)
    override suspend fun denyInvite(id: String) = current().denyInvite(id)
    override suspend fun joinInvite(id: String) = current().joinInvite(id)
    override suspend fun declineInvite(id: String) = current().declineInvite(id)
    override suspend fun transferChatOwner(chatId: String, deviceId: String) = current().transferChatOwner(chatId, deviceId)
    override suspend fun removeChatMember(chatId: String, deviceId: String) = current().removeChatMember(chatId, deviceId)
    override suspend fun adminJoinChat(chatId: String) = current().adminJoinChat(chatId)
    override suspend fun syncChat(chatId: String, sinceRev: Long) = current().syncChat(chatId, sinceRev)
    override suspend fun postMessage(chatId: String, text: String, mentions: List<String>) = current().postMessage(chatId, text, mentions)
    override suspend fun editMessage(chatId: String, msgId: String, text: String, mentions: List<String>) = current().editMessage(chatId, msgId, text, mentions)
    override suspend fun deleteMessage(chatId: String, msgId: String) = current().deleteMessage(chatId, msgId)
    override suspend fun markChatRead(chatId: String, rev: Long) = current().markChatRead(chatId, rev)
    override suspend fun markChatSeen(chatId: String, rev: Long) = current().markChatSeen(chatId, rev)
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
