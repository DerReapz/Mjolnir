package app.mjolnir.server

import app.mjolnir.protocol.ClaimRequest
import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.PushTokenRequest
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.MjolnirJson
import app.mjolnir.protocol.RegisterRequest
import app.mjolnir.protocol.RegisterResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.SendRequest
import app.mjolnir.protocol.SendResponse
import app.mjolnir.protocol.Tab
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import app.mjolnir.protocol.CreateUploadRequest
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.UploadStatus
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class FakeClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs(): Long = now
}

class EventReader(private val incoming: kotlinx.coroutines.channels.ReceiveChannel<Frame>) {
    suspend fun next(): RelayEvent = withTimeout(5_000) {
        val frame = incoming.receive() as Frame.Text
        MjolnirJson.decodeFromString(RelayEvent.serializer(), frame.readText())
    }
}

class RelayTestScope(
    val client: HttpClient,
    val deps: RelayDeps,
    val clock: FakeClock,
) {
    val store: Store get() = deps.store

    suspend fun register(name: String = "Pixel 8", kind: DeviceKind = DeviceKind.PHONE): RegisterResponse =
        client.post("/v1/devices") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(name, kind))
        }.body()

    suspend fun createCode(owner: RegisterResponse): String =
        client.post("/v1/pairings") { bearerAuth(owner.token) }.body<PairingCodeResponse>().code

    suspend fun claimStatus(device: RegisterResponse, code: String): HttpStatusCode =
        client.post("/v1/pairings/claim") { bearerAuth(device.token); contentType(ContentType.Application.Json); setBody(ClaimRequest(code)) }.status

    suspend fun claim(device: RegisterResponse, code: String): ClaimResponse =
        client.post("/v1/pairings/claim") { bearerAuth(device.token); contentType(ContentType.Application.Json); setBody(ClaimRequest(code)) }.body()

    suspend fun status(device: RegisterResponse, pairingId: String): PairingStatus =
        client.get("/v1/pairings/$pairingId") { bearerAuth(device.token) }.body()

    suspend fun peers(device: RegisterResponse): List<Peer> =
        client.get("/v1/peers") { bearerAuth(device.token) }.body()

    suspend fun pair(owner: RegisterResponse, claimer: RegisterResponse) {
        val code = createCode(owner)
        val c = claim(claimer, code)
        check(client.post("/v1/pairings/${c.pairingId}/accept") { bearerAuth(owner.token) }.status == HttpStatusCode.NoContent)
    }

    suspend fun pairedPcAndPhone(): Pair<RegisterResponse, RegisterResponse> {
        val pc = register("PC", DeviceKind.PC)
        val phone = register("Pixel 8")
        pair(pc, phone)
        return pc to phone
    }

    suspend fun sendStatus(from: RegisterResponse, to: String, tabs: List<Tab>): HttpStatusCode =
        client.post("/v1/send") { bearerAuth(from.token); contentType(ContentType.Application.Json); setBody(SendRequest(to, tabs)) }.status

    suspend fun send(from: RegisterResponse, to: String, tabs: List<Tab>): SendResponse {
        val r = client.post("/v1/send") { bearerAuth(from.token); contentType(ContentType.Application.Json); setBody(SendRequest(to, tabs)) }
        check(r.status == HttpStatusCode.Accepted) { "send failed: ${r.status}" }
        return r.body()
    }

    suspend fun inbox(device: RegisterResponse): List<Delivery> =
        client.get("/v1/inbox") { bearerAuth(device.token) }.body()

    suspend fun createUploadResponse(device: RegisterResponse, relativePath: String, mime: String, size: Long): HttpResponse =
        client.post("/v1/uploads") {
            bearerAuth(device.token); contentType(ContentType.Application.Json)
            setBody(CreateUploadRequest(relativePath, mime, size))
        }

    suspend fun createUpload(device: RegisterResponse, relativePath: String, mime: String, size: Long): UploadStatus {
        val r = createUploadResponse(device, relativePath, mime, size)
        check(r.status == HttpStatusCode.Created) { "create upload failed: ${r.status}" }
        return r.body()
    }

    /** PUTs bytes[from, to) with `Content-Range: bytes from-(to-1)/total`. */
    suspend fun putChunkResponse(
        device: RegisterResponse, uploadId: String, bytes: ByteArray, from: Int, to: Int, total: Long = bytes.size.toLong(),
    ): HttpResponse =
        client.put("/v1/uploads/$uploadId") {
            bearerAuth(device.token)
            header(HttpHeaders.ContentRange, "bytes $from-${to - 1}/$total")
            contentType(ContentType.Application.OctetStream)
            setBody(bytes.copyOfRange(from, to))
        }

    suspend fun putChunk(device: RegisterResponse, uploadId: String, bytes: ByteArray, from: Int, to: Int): UploadStatus {
        val r = putChunkResponse(device, uploadId, bytes, from, to)
        check(r.status == HttpStatusCode.OK) { "put chunk failed: ${r.status}" }
        return r.body()
    }

    suspend fun upload(
        device: RegisterResponse, relativePath: String, bytes: ByteArray, mime: String = "application/octet-stream",
    ): FileRef {
        val s = createUpload(device, relativePath, mime, bytes.size.toLong())
        if (bytes.isEmpty()) return s.file!!
        return putChunk(device, s.uploadId, bytes, 0, bytes.size).file!!
    }

    suspend fun sendFiles(from: RegisterResponse, to: String, fileIds: List<String>): HttpResponse =
        client.post("/v1/send") {
            bearerAuth(from.token); contentType(ContentType.Application.Json)
            setBody(SendRequest(to, fileIds = fileIds))
        }

    suspend fun setPushToken(device: RegisterResponse, token: String): HttpStatusCode =
        client.put("/v1/devices/me/push-token") {
            bearerAuth(device.token); contentType(ContentType.Application.Json); setBody(PushTokenRequest(token))
        }.status

    suspend fun <T> withEvents(device: RegisterResponse, block: suspend EventReader.() -> T): T =
        run {
            var result: Result<T>? = null
            client.webSocket("/v1/ws", request = { bearerAuth(device.token) }) {
                withTimeout(5_000) { while (!deps.hub.isConnected(device.deviceId)) delay(10) }
                result = runCatching { EventReader(incoming).block() }
            }
            result!!.getOrThrow()
        }
}

fun relayTest(
    clock: FakeClock = FakeClock(),
    limits: RelayLimits = RelayLimits(),
    files: FileStorage = FileStorage(createTempDirectory("mjolnir-files").toFile()),
    push: PushSender = NoopPushSender,
    heartbeatMs: Long = 30_000,
    block: suspend RelayTestScope.() -> Unit,
) = testApplication {
    val deps = RelayDeps(store = Store("jdbc:sqlite::memory:"), clock = clock, limits = limits, files = files, push = push, heartbeatMs = heartbeatMs)
    application { relayModule(deps) }
    val client = createClient {
        install(ContentNegotiation) { json(MjolnirJson) }
        install(WebSockets)
    }
    RelayTestScope(client, deps, clock).block()
}
