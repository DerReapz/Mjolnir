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
