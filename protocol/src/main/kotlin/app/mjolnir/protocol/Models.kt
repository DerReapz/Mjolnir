package app.mjolnir.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class DeviceKind { PHONE, PC }
@Serializable data class Tab(val url: String, val title: String? = null)
@Serializable data class Peer(val deviceId: String, val name: String, val kind: DeviceKind)
@Serializable data class RegisterRequest(val name: String, val kind: DeviceKind)
@Serializable data class RegisterResponse(val deviceId: String, val token: String)
@Serializable data class RenameRequest(val name: String)
@Serializable data class PushTokenRequest(val token: String)
@Serializable data class PairingCodeResponse(val code: String, val expiresAtEpochMs: Long)
@Serializable data class ClaimRequest(val code: String)
@Serializable data class ClaimResponse(val pairingId: String, val owner: Peer)
@Serializable enum class PairingState { PENDING, CLAIMED, ACCEPTED, REJECTED, EXPIRED }
@Serializable data class PairingStatus(val state: PairingState, val peer: Peer? = null)
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
)
@Serializable data class SendResponse(val deliveryId: String)
@Serializable data class Delivery(
    val id: String,
    val from: Peer,
    val tabs: List<Tab>,
    val sentAtEpochMs: Long,
    val files: List<FileRef> = emptyList(),
)
@Serializable data class ErrorResponse(val error: String)

@Serializable
sealed interface RelayEvent {
    @Serializable @SerialName("delivery") data class DeliveryArrived(val delivery: Delivery) : RelayEvent
    @Serializable @SerialName("pair_requested") data class PairRequested(val pairingId: String, val from: Peer) : RelayEvent
    @Serializable @SerialName("paired") data class Paired(val peer: Peer) : RelayEvent
    @Serializable @SerialName("unpaired") data class Unpaired(val deviceId: String) : RelayEvent

    /** Emitted by RelayClient after every (re)connect; the server never sends it. */
    @Serializable @SerialName("connected") data object Connected : RelayEvent
}

val MjolnirJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    encodeDefaults = true
}
