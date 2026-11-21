package app.mjolnir.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** GUEST: a browser in a guest room, linked only to the room's owner and gone when the room ends. */
@Serializable(with = DeviceKindSerializer::class) enum class DeviceKind { PHONE, PC, GUEST }

/** Decodes leniently: a kind this version doesn't know (a newer relay's) reads as PC rather than failing the whole message. */
object DeviceKindSerializer : KSerializer<DeviceKind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.mjolnir.protocol.DeviceKind", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: DeviceKind) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): DeviceKind {
        val name = decoder.decodeString()
        return DeviceKind.entries.firstOrNull { it.name == name } ?: DeviceKind.PC
    }
}
@Serializable data class Tab(val url: String, val title: String? = null)
@Serializable data class SpaceRef(val id: String, val name: String)
@Serializable data class Peer(val deviceId: String, val name: String, val kind: DeviceKind, val space: SpaceRef? = null)
@Serializable data class RegisterRequest(val name: String, val kind: DeviceKind)
@Serializable data class RegisterResponse(val deviceId: String, val token: String)
@Serializable data class RenameRequest(val name: String)
@Serializable data class PushTokenRequest(val token: String)
@Serializable data class PairingCodeResponse(val code: String, val expiresAtEpochMs: Long)
@Serializable enum class CodeKind { PAIRING, SPACE_INVITE }
@Serializable data class ClaimRequest(val code: String, val expect: CodeKind = CodeKind.PAIRING)
@Serializable data class ClaimResponse(
    val pairingId: String,
    val owner: Peer,
    val kind: CodeKind = CodeKind.PAIRING,
    val space: SpaceRef? = null,
)
@Serializable enum class PairingState { PENDING, CLAIMED, ACCEPTED, REJECTED, EXPIRED }
@Serializable data class PairingStatus(val state: PairingState, val peer: Peer? = null, val space: SpaceRef? = null)
@Serializable data class FileRef(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val sha256: String,
    val relativePath: String = name,
)
@Serializable data class CreateUploadRequest(val relativePath: String, val mimeType: String, val sizeBytes: Long)
@Serializable data class UploadStatus(val uploadId: String, val offset: Long, val file: FileRef? = null)
@Serializable data class SendRequest(
    val to: String,
    val tabs: List<Tab> = emptyList(),
    val fileIds: List<String> = emptyList(),
    val spaceId: String? = null,
)
@Serializable data class SendResponse(val deliveryId: String)
@Serializable data class Delivery(
    val id: String,
    val from: Peer,
    val tabs: List<Tab>,
    val sentAtEpochMs: Long,
    val files: List<FileRef> = emptyList(),
    val space: SpaceRef? = null,
)
@Serializable data class Space(
    val id: String,
    val name: String,
    val adminId: String,
    val members: List<Peer>,
    /** True when the admin has not connected for over 30 days and is not connected now (the exact time is not shared). */
    val adminSilent: Boolean = false,
)
@Serializable data class CreateSpaceRequest(val name: String)
@Serializable data class RenameSpaceRequest(val name: String)
@Serializable data class TransferAdminRequest(val deviceId: String)
@Serializable data class RoomResponse(val roomId: String, val secret: String, val expiresAtEpochMs: Long)
/** [downloadedFileIds]: files sent to the room's guest that the guest has already downloaded, so an owner app that wasn't listening can catch up. */
@Serializable data class RoomStatus(
    val roomId: String,
    val expiresAtEpochMs: Long,
    val serverNowEpochMs: Long,
    val guest: Peer?,
    val downloadedFileIds: List<String> = emptyList(),
)
/** Either [fileId], or [deliveryId] with the top [folder] to download as one zip. */
@Serializable data class TicketRequest(val fileId: String? = null, val deliveryId: String? = null, val folder: String? = null)
@Serializable data class TicketResponse(val ticket: String)
@Serializable data class GuestJoinRequest(val secret: String)
@Serializable data class GuestJoinResponse(
    val deviceId: String,
    val token: String,
    val roomId: String,
    val owner: Peer,
    val expiresAtEpochMs: Long,
    val maxFileMb: Int,
)

@Serializable enum class InviteState { PENDING_APPROVAL, APPROVED, DENIED, DECLINED }
/** A membership or settings change shown inline in the chat; recorded as a body-less [ChatMessage] authored by the actor. */
@Serializable
sealed interface ChatEvent {
    @Serializable @SerialName("member_removed") data class MemberRemoved(val deviceId: String, val byAdmin: Boolean) : ChatEvent
    @Serializable @SerialName("member_left") data class MemberLeft(val deviceId: String) : ChatEvent
    @Serializable @SerialName("member_joined") data class MemberJoined(val deviceId: String) : ChatEvent
    @Serializable @SerialName("owner_changed") data class OwnerChanged(val deviceId: String) : ChatEvent
    @Serializable @SerialName("renamed") data class Renamed(val name: String) : ChatEvent
}
@Serializable enum class AttachmentKind { IMAGE, GIF, VIDEO, FILE }
/** A file attached to a chat message: [fileId] (and [thumbFileId]) name relay files downloadable by the chat's members. */
@Serializable data class Attachment(
    val fileId: String,
    val kind: AttachmentKind,
    val name: String,
    val sizeBytes: Long,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbFileId: String? = null,
    /** Path inside a sent folder (top folder first); null for a single file. */
    val relativePath: String? = null,
)
@Serializable data class ChatMessage(
    val id: String,
    val chatId: String,
    val author: Peer,
    val rev: Long,
    val sentAtEpochMs: Long,
    val editedAtEpochMs: Long? = null,
    val deleted: Boolean = false,
    val deletedByAdmin: Boolean = false,
    val text: String? = null,
    val event: ChatEvent? = null,
    /** Device ids of the chat members this message mentions (always members when posted); never set on deleted messages. */
    val mentions: List<String> = emptyList(),
    /** The chat rev at which this message first appeared; edits and deletes change [rev] but never this. Receipts compare against it (null: use [rev]). */
    val createdRev: Long? = null,
    val attachments: List<Attachment> = emptyList(),
)
@Serializable data class ChatSummary(
    val id: String,
    val spaceId: String,
    val spaceName: String,
    val name: String,
    val ownerId: String,
    val rev: Long,
    val readRev: Long,
    val member: Boolean,
    val lastMessage: ChatMessage?,
)
@Serializable data class ChatSync(
    val chatId: String,
    val rev: Long,
    val ownerId: String,
    val members: List<Peer>,
    val messages: List<ChatMessage>,
    val hasMore: Boolean,
    /** One per current member, never for a space admin who isn't in the chat. */
    val receipts: List<Receipt> = emptyList(),
)
/** When [deviceId] first saw and first read one message: the first mark at or above its rev. Null while it has not. */
@Serializable data class MessageReceipt(val deviceId: String, val seenAtEpochMs: Long? = null, val readAtEpochMs: Long? = null)
/** How far [deviceId] has got in a chat: [seenRev] reached its device, [readRev] was on screen. The times are null while the rev is 0. */
@Serializable data class Receipt(
    val deviceId: String,
    val seenRev: Long,
    val readRev: Long,
    val seenAtEpochMs: Long? = null,
    val readAtEpochMs: Long? = null,
)
@Serializable data class ChatInvite(
    val id: String,
    val chatId: String,
    val chatName: String,
    val spaceName: String,
    val invitee: Peer,
    val inviter: Peer,
    val state: InviteState,
)
@Serializable data class CreateChatRequest(val name: String)
@Serializable data class RenameChatRequest(val name: String)
@Serializable data class InviteRequest(val deviceId: String)
@Serializable data class TransferOwnerRequest(val deviceId: String)
@Serializable data class PostMessageRequest(
    val text: String? = null,
    val mentions: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
)
@Serializable data class EditMessageRequest(val text: String, val mentions: List<String> = emptyList())
@Serializable data class ReadRequest(val rev: Long)
@Serializable data class SeenRequest(val rev: Long)
@Serializable data class ErrorResponse(val error: String, val detail: String? = null)

@Serializable
sealed interface RelayEvent {
    @Serializable @SerialName("delivery") data class DeliveryArrived(val delivery: Delivery) : RelayEvent
    @Serializable @SerialName("pair_requested") data class PairRequested(val pairingId: String, val from: Peer, val space: SpaceRef? = null) : RelayEvent
    @Serializable @SerialName("paired") data class Paired(val peer: Peer) : RelayEvent
    @Serializable @SerialName("unpaired") data class Unpaired(val deviceId: String) : RelayEvent

    @Serializable @SerialName("space_changed") data class SpaceChanged(val spaceId: String) : RelayEvent
    @Serializable @SerialName("space_deleted") data class SpaceDeleted(val space: SpaceRef) : RelayEvent

    /**
     * Sent to a chat's members (and its space admin) when the chat's messages or members changed; [rev] is the chat's revision.
     * [mentioned] is set for a device a new message mentions; [receipts] carries the receipts that changed when only who has
     * seen or read moved (the rev stays), so receivers need no sync.
     */
    @Serializable @SerialName("chat_changed") data class ChatChanged(
        val chatId: String,
        val rev: Long,
        val mentioned: Boolean = false,
        val receipts: List<Receipt> = emptyList(),
    ) : RelayEvent
    @Serializable @SerialName("chat_deleted") data class ChatDeleted(val chatId: String) : RelayEvent
    /** Sent when the receiver's list of actionable chat invites changed. */
    @Serializable @SerialName("chat_invites_changed") data object ChatInvitesChanged : RelayEvent

    /** Sent to a room's owner when the guest redeems the invite. */
    @Serializable @SerialName("guest_joined") data class GuestJoined(val roomId: String, val guest: Peer) : RelayEvent
    /** Sent to a room's owner when the room ends (End room or expiry). */
    @Serializable @SerialName("room_ended") data class RoomEnded(val roomId: String) : RelayEvent
    /** Sent to the sender when the guest has been served the last byte of these files of [deliveryId]. */
    @Serializable @SerialName("downloaded") data class Downloaded(val deliveryId: String, val fileIds: List<String>) : RelayEvent

    /** Emitted by RelayClient after every (re)connect; the server never sends it. */
    @Serializable @SerialName("connected") data object Connected : RelayEvent

    /** Emitted by RelayClient when an established connection drops (before it reconnects); the server never sends it. */
    @Serializable @SerialName("disconnected") data object Disconnected : RelayEvent
}

val MjolnirJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    encodeDefaults = true
}
