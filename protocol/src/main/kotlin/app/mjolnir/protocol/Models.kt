package app.mjolnir.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class DeviceKind { PHONE, PC }
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
    val adminLastSeenEpochMs: Long,
)
@Serializable data class CreateSpaceRequest(val name: String)
@Serializable data class RenameSpaceRequest(val name: String)
@Serializable data class TransferAdminRequest(val deviceId: String)
@Serializable data class ErrorResponse(val error: String)

@Serializable
sealed interface RelayEvent {
    @Serializable @SerialName("delivery") data class DeliveryArrived(val delivery: Delivery) : RelayEvent
    @Serializable @SerialName("pair_requested") data class PairRequested(val pairingId: String, val from: Peer, val space: SpaceRef? = null) : RelayEvent
    @Serializable @SerialName("paired") data class Paired(val peer: Peer) : RelayEvent
    @Serializable @SerialName("unpaired") data class Unpaired(val deviceId: String) : RelayEvent

    @Serializable @SerialName("space_changed") data class SpaceChanged(val spaceId: String) : RelayEvent
    @Serializable @SerialName("space_deleted") data class SpaceDeleted(val space: SpaceRef) : RelayEvent

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
