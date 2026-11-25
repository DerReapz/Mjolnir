package app.mjolnir.client

import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.ChatSync
import app.mjolnir.protocol.MessageReceipt
import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RegisterResponse
import app.mjolnir.protocol.RoomResponse
import app.mjolnir.protocol.RoomStatus
import app.mjolnir.protocol.SendResponse
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.UploadStatus
import app.mjolnir.protocol.VoiceRoom
import app.mjolnir.client.voice.VoiceLink
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * RELAY_OUTDATED: a 404 without the relay's JSON error body, i.e. a route an older relay doesn't have.
 * APP_OUTDATED: a 426 (any body), a relay that needs a newer app; retried like a network error, so queued sends survive
 * the update.
 */
enum class RelayErrorKind { UNAUTHORIZED, NOT_PAIRED, INVALID, NOT_FOUND, RATE_LIMITED, TOO_LARGE, STORAGE_FULL, ALREADY_SENT, SERVER, NETWORK, RELAY_OUTDATED, APP_OUTDATED }

/** [userMessage], when set, is a ready-made reason for the user (e.g. "Couldn't read a.jpg") that beats the kind's generic one. */
class RelayException(
    val kind: RelayErrorKind,
    message: String,
    cause: Throwable? = null,
    val userMessage: String? = null,
    /** The relay's `detail` for the error, e.g. the space name for already_member. */
    val detail: String? = null,
    /**
     * The answer came from a relay older than this app (its `Mjolnir-Relay-Version` is lower). Copy that would otherwise
     * be generic says the relay needs updating instead; see [needsRelayUpdate].
     */
    val fromOlderRelay: Boolean = false,
) : Exception(message, cause)

/** True when this error should show [Messages.RELAY_OUTDATED] in place of a generic error. */
val RelayException.needsRelayUpdate: Boolean get() = kind == RelayErrorKind.RELAY_OUTDATED || fromOlderRelay

/** How to fetch a file straight from the relay (e.g. for a streaming player): its [url] and the [headers] to send. */
data class FileRequest(val url: String, val headers: Map<String, String>)

interface RelayApi {
    suspend fun register(name: String, kind: DeviceKind): RegisterResponse
    suspend fun me(): Peer
    suspend fun rename(name: String): Peer
    suspend fun setPushToken(pushToken: String)
    suspend fun createPairingCode(): PairingCodeResponse
    /** [expect] is the kind of code the caller believes this is; the relay answers 400 wrong_kind_* when it differs. */
    suspend fun claimPairingCode(code: String, expect: CodeKind = CodeKind.PAIRING): ClaimResponse
    suspend fun pairingStatus(pairingId: String): PairingStatus
    suspend fun acceptPairing(pairingId: String)
    suspend fun rejectPairing(pairingId: String)
    suspend fun peers(): List<Peer>
    suspend fun unpair(deviceId: String)
    suspend fun send(to: String, tabs: List<Tab>, fileIds: List<String> = emptyList(), spaceId: String? = null): SendResponse

    suspend fun createSpace(name: String): Space
    suspend fun spaces(): List<Space>
    suspend fun renameSpace(id: String, name: String): Space
    suspend fun deleteSpace(id: String)
    suspend fun createInvite(spaceId: String): PairingCodeResponse
    /** Admin removes [deviceId]; a member removing themselves is leaving. */
    suspend fun removeMember(spaceId: String, deviceId: String)
    suspend fun transferAdmin(spaceId: String, deviceId: String): Space
    suspend fun takeOverAdmin(spaceId: String): Space
    /** Leaves [spaceId]: [removeMember] of this device. */
    suspend fun leaveSpace(spaceId: String) = removeMember(spaceId, me().deviceId)

    // ---- Chats. Errors carry the relay's code as the exception message (not_owner, too_long, 429 as RATE_LIMITED, ...). ----

    /** The chats of [spaceId] that this device may see: its own, or all of them for the space's admin. */
    suspend fun chats(spaceId: String): List<ChatSummary>
    suspend fun createChat(spaceId: String, name: String): ChatSummary
    /** Creates an open channel everyone in the space is in; an older relay answers RELAY_OUTDATED. */
    suspend fun createChannel(spaceId: String, name: String): ChatSummary
    /** Mutes or unmutes [chatId] for this device (the relay's doorbell skips muted chats unless mentioned). */
    suspend fun setChatMuted(chatId: String, muted: Boolean): ChatSummary
    suspend fun renameChat(id: String, name: String): ChatSummary
    suspend fun deleteChat(id: String)

    /** Any member invites a space member; the owner's invites are approved at once. */
    suspend fun inviteToChat(chatId: String, deviceId: String): ChatInvite
    /** The invites this device can act on: approvals it owes as owner and its own approved invitations. */
    suspend fun chatInvites(): List<ChatInvite>
    suspend fun approveInvite(id: String)
    suspend fun denyInvite(id: String)
    suspend fun joinInvite(id: String): ChatSummary
    suspend fun declineInvite(id: String)

    suspend fun transferChatOwner(chatId: String, deviceId: String): ChatSummary
    /** The owner can't remove itself (owner_must_transfer); the space admin removes others; anyone else removes only themselves. */
    suspend fun removeChatMember(chatId: String, deviceId: String)
    /** The space admin joins a chat without approval. */
    suspend fun adminJoinChat(chatId: String): ChatSummary

    /** One page (200) of the messages with rev above [sinceRev], including edited and deleted ones; see [ChatSync.hasMore]. */
    suspend fun syncChat(chatId: String, sinceRev: Long): ChatSync
    /**
     * [mentions] are device ids of chat members (anyone else: 400 invalid_mention). [text] may be null when there are
     * [attachments] (at most 20): finished uploads of this device not yet used anywhere (else 400 invalid_files; a file
     * over the chat limit: TOO_LARGE). The relay fills in each attachment's size and mime from the upload.
     */
    suspend fun postMessage(chatId: String, text: String?, mentions: List<String> = emptyList(), attachments: List<Attachment> = emptyList()): ChatMessage
    suspend fun editMessage(chatId: String, msgId: String, text: String, mentions: List<String> = emptyList()): ChatMessage
    suspend fun deleteMessage(chatId: String, msgId: String)
    suspend fun markChatRead(chatId: String, rev: Long)
    /** Tells the relay that everything up to [rev] reached this device (members only: not_member otherwise). */
    suspend fun markChatSeen(chatId: String, rev: Long)
    /** When each member first saw and first read the message ([msgId]): what the message's Info shows. */
    suspend fun messageReceipts(chatId: String, msgId: String): List<MessageReceipt>

    // ---- Voice rooms. An older relay answers the list with RELAY_OUTDATED. ----

    /** The voice rooms of [spaceId], each with who is in it. */
    suspend fun voiceRooms(spaceId: String): List<VoiceRoom>
    suspend fun createVoiceRoom(spaceId: String, name: String): VoiceRoom
    /** The room's creator or the space admin only (else NOT_PAIRED `not_owner`). */
    suspend fun renameVoiceRoom(id: String, name: String): VoiceRoom
    suspend fun deleteVoiceRoom(id: String)
    /**
     * Opens the voice socket of [roomId] in the background and returns at once; see [VoiceLink]. [onRoster] and [onFrame]
     * are called from the socket's reader, one at a time.
     */
    fun openVoice(roomId: String, onRoster: (List<String>) -> Unit, onFrame: (speakerIndex: Int, seq: Int, opus: ByteArray) -> Unit): VoiceLink

    /** Opens the guest room (ALREADY_SENT when one is open: 409 room_open). */
    suspend fun openRoom(): RoomResponse
    /** The open room, or null (204). */
    suspend fun currentRoom(): RoomStatus?
    suspend fun endRoom(id: String)

    suspend fun inbox(): List<Delivery>
    suspend fun ack(deliveryId: String)

    /** Starts a resumable upload session (TOO_LARGE / STORAGE_FULL when the relay refuses it). */
    suspend fun createUpload(relativePath: String, mimeType: String, sizeBytes: Long): UploadStatus
    /** Current offset of an upload session, or the finished [UploadStatus.file]; NOT_FOUND once the session is gone. */
    suspend fun uploadStatus(uploadId: String): UploadStatus
    /** Sends `bytes[0 until length]` at [start]. A 409 (offset mismatch) returns the server's status instead of throwing. */
    suspend fun uploadChunk(uploadId: String, start: Long, total: Long, bytes: ByteArray, length: Int): UploadStatus
    /**
     * Downloads [ref] into [target], resuming from its length when it exists and is ≤ ref.sizeBytes (else starting over).
     * Checks size and SHA-256 at the end; a mismatch deletes [target] and throws RelayException(INVALID).
     * [onProgress] receives the number of bytes of [target] present so far.
     */
    suspend fun downloadFile(ref: FileRef, target: File, onProgress: (Long) -> Unit = {})
    /**
     * Downloads chat file [fileId] (an [Attachment.fileId] or [Attachment.thumbFileId]) whole into [target], replacing it.
     * NOT_FOUND once its message is deleted. On any failure [target] is deleted.
     */
    suspend fun downloadChatFile(fileId: String, target: File, onProgress: (Long) -> Unit = {})
    /**
     * The request for chat file [fileId] on the relay (`GET /v1/files/{id}`, ranged requests allowed), with this device's
     * bearer token when it has one, for a player that streams it. Reads the current token, which may touch the disk:
     * call it off the main thread. The relay answers 401 for a rejected token and 404 once the message is deleted.
     */
    fun chatFileRequest(fileId: String): FileRequest

    /** Cold flow of live events; reconnects with backoff, throws RelayException(UNAUTHORIZED) and stops if the token is rejected. */
    fun events(): Flow<RelayEvent>
}
