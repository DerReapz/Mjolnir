package app.mjolnir.client

import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.RegisterResponse
import app.mjolnir.protocol.SendResponse
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.Tab
import kotlinx.coroutines.flow.Flow

enum class RelayErrorKind { UNAUTHORIZED, NOT_PAIRED, INVALID, NOT_FOUND, RATE_LIMITED, TOO_LARGE, STORAGE_FULL, ALREADY_SENT, SERVER, NETWORK }

class RelayException(val kind: RelayErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

interface RelayApi {
    suspend fun register(name: String, kind: DeviceKind): RegisterResponse
    suspend fun me(): Peer
    suspend fun rename(name: String): Peer
    suspend fun setPushToken(pushToken: String)
    suspend fun createPairingCode(): PairingCodeResponse
    suspend fun claimPairingCode(code: String): ClaimResponse
    suspend fun pairingStatus(pairingId: String): PairingStatus
    suspend fun acceptPairing(pairingId: String)
    suspend fun rejectPairing(pairingId: String)
    suspend fun peers(): List<Peer>
    suspend fun unpair(deviceId: String)
    suspend fun send(to: String, tabs: List<Tab>, fileIds: List<String> = emptyList()): SendResponse
    suspend fun inbox(): List<Delivery>
    suspend fun ack(deliveryId: String)

    /** Cold flow of live events; reconnects with backoff, throws RelayException(UNAUTHORIZED) and stops if the token is rejected. */
    fun events(): Flow<RelayEvent>
}
