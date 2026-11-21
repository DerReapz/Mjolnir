package app.mjolnir.client

import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.ChatInvite
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.ChatSummary
import app.mjolnir.protocol.ChatSync
import app.mjolnir.protocol.MessageReceipt
import app.mjolnir.protocol.ClaimRequest
import app.mjolnir.protocol.CreateChatRequest
import app.mjolnir.protocol.EditMessageRequest
import app.mjolnir.protocol.InviteRequest
import app.mjolnir.protocol.PostMessageRequest
import app.mjolnir.protocol.ReadRequest
import app.mjolnir.protocol.RenameChatRequest
import app.mjolnir.protocol.SeenRequest
import app.mjolnir.protocol.TransferOwnerRequest
import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.CreateSpaceRequest
import app.mjolnir.protocol.RenameSpaceRequest
import app.mjolnir.protocol.RoomResponse
import app.mjolnir.protocol.RoomStatus
import app.mjolnir.protocol.Space
import app.mjolnir.protocol.TransferAdminRequest
import app.mjolnir.protocol.Delivery
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.MjolnirJson
import app.mjolnir.protocol.PairingCodeResponse
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.PushTokenRequest
import app.mjolnir.protocol.RegisterRequest
import app.mjolnir.protocol.RegisterResponse
import app.mjolnir.protocol.RenameRequest
import app.mjolnir.protocol.SendRequest
import app.mjolnir.protocol.SendResponse
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.CreateUploadRequest
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.UploadStatus
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import app.mjolnir.protocol.RelayEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun HttpClientConfig<*>.installMjolnirDefaults() {
    install(ContentNegotiation) { json(MjolnirJson) }
    install(WebSockets) { pingIntervalMillis = 20_000 }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
    }
}

/** The Ktor client every relay call uses: OkHttp engine plus [installMjolnirDefaults]. */
fun newMjolnirHttpClient(): HttpClient = HttpClient(OkHttp) {
    installMjolnirDefaults()
    engine { config { pingInterval(20, TimeUnit.SECONDS) } }
}

private fun isHeartbeat(text: String): Boolean = try {
    (MjolnirJson.parseToJsonElement(text) as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "heartbeat"
} catch (_: Exception) {
    false
}

private const val TRANSFER_SOCKET_TIMEOUT_MS = 60_000L
private const val BUFFER_BYTES = 64 * 1024

/** Lower-case hex SHA-256 of [file], streamed. */
internal fun sha256HexOf(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(BUFFER_BYTES)
    file.inputStream().use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

internal fun backoffMs(attempt: Int): Long = min(30_000L, 1_000L shl attempt.coerceIn(0, 20))

class RelayClient(
    private val http: HttpClient,
    baseUrl: String,
    private val reconnectDelayMs: (Int) -> Long = ::backoffMs,
    private val idleTimeoutMs: Long = 75_000,
    private val stableAfterMs: Long = 30_000,
    /** Socket (idle read/write) timeout for chunk uploads and downloads, which have no overall deadline; null = none. */
    private val transferSocketTimeoutMs: Long? = TRANSFER_SOCKET_TIMEOUT_MS,
    private val token: () -> String?,
) : RelayApi, AutoCloseable {
    private val base = baseUrl.trimEnd('/')
    private val closed = Job()

    /** Closes the HTTP client and ends any active [events] flow cleanly. */
    override fun close() {
        closed.complete()
        http.close()
    }

    companion object {
        fun create(baseUrl: String, token: () -> String?): RelayClient =
            RelayClient(
                newMjolnirHttpClient(),
                baseUrl,
                token = token,
            )
    }

    private fun HttpRequestBuilder.auth() { token()?.let { bearerAuth(it) } }

    /** Requests for whole files or 8 MiB chunks: no overall deadline (slow links), but a stalled socket still fails. */
    private fun HttpRequestBuilder.transferTimeout() {
        timeout {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            transferSocketTimeoutMs?.let { socketTimeoutMillis = it }
        }
    }

    private fun networkError(e: Exception) = RelayException(RelayErrorKind.NETWORK, e.message ?: "network error", e)

    /** Maps a non-2xx response to its [RelayException]. */
    private suspend fun errorFor(response: HttpResponse): RelayException {
        val code = response.status.value
        val parsed = try {
            MjolnirJson.decodeFromString(ErrorResponse.serializer(), response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val message = parsed?.error ?: "HTTP $code"
        val kind = when (code) {
            401 -> RelayErrorKind.UNAUTHORIZED
            403 -> RelayErrorKind.NOT_PAIRED
            400 -> RelayErrorKind.INVALID
            404 -> RelayErrorKind.NOT_FOUND
            409 -> RelayErrorKind.ALREADY_SENT
            413 -> RelayErrorKind.TOO_LARGE
            429 -> RelayErrorKind.RATE_LIMITED
            507 -> RelayErrorKind.STORAGE_FULL
            else -> if (code >= 500) RelayErrorKind.SERVER else RelayErrorKind.INVALID
        }
        return RelayException(kind, message, detail = parsed?.detail)
    }

    /** Runs [block]; 2xx and any status in [okCodes] are returned, everything else becomes a [RelayException]. */
    private suspend fun call(okCodes: Set<Int> = emptySet(), block: suspend () -> HttpResponse): HttpResponse {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOExceptions, Ktor timeout exceptions and engine failures that are not IOExceptions.
            throw networkError(e)
        }
        val code = response.status.value
        if (code in 200..299 || code in okCodes) return response
        throw errorFor(response)
    }

    private suspend inline fun <reified T> callBody(
        okCodes: Set<Int> = emptySet(),
        noinline block: suspend () -> HttpResponse,
    ): T {
        val response = call(okCodes, block)
        return try {
            response.body<T>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw RelayException(RelayErrorKind.NETWORK, e.message ?: "network error", e)
        } catch (e: Exception) {
            throw RelayException(RelayErrorKind.SERVER, "unreadable response: ${e.message}", e)
        }
    }

    override suspend fun register(name: String, kind: DeviceKind): RegisterResponse = callBody {
        http.post("$base/v1/devices") { auth(); contentType(ContentType.Application.Json); setBody(RegisterRequest(name, kind)) }
    }

    override suspend fun me(): Peer = callBody { http.get("$base/v1/devices/me") { auth() } }

    override suspend fun rename(name: String): Peer = callBody {
        http.patch("$base/v1/devices/me") { auth(); contentType(ContentType.Application.Json); setBody(RenameRequest(name)) }
    }

    override suspend fun setPushToken(pushToken: String) {
        call { http.put("$base/v1/devices/me/push-token") { auth(); contentType(ContentType.Application.Json); setBody(PushTokenRequest(pushToken)) } }
    }

    override suspend fun createPairingCode(): PairingCodeResponse = callBody { http.post("$base/v1/pairings") { auth() } }

    override suspend fun claimPairingCode(code: String, expect: CodeKind): ClaimResponse = callBody {
        http.post("$base/v1/pairings/claim") { auth(); contentType(ContentType.Application.Json); setBody(ClaimRequest(code, expect)) }
    }

    override suspend fun pairingStatus(pairingId: String): PairingStatus = callBody { http.get("$base/v1/pairings/$pairingId") { auth() } }

    override suspend fun acceptPairing(pairingId: String) { call { http.post("$base/v1/pairings/$pairingId/accept") { auth() } } }

    override suspend fun rejectPairing(pairingId: String) { call { http.post("$base/v1/pairings/$pairingId/reject") { auth() } } }

    override suspend fun peers(): List<Peer> = callBody { http.get("$base/v1/peers") { auth() } }

    override suspend fun unpair(deviceId: String) { call { http.delete("$base/v1/peers/$deviceId") { auth() } } }

    override suspend fun send(to: String, tabs: List<Tab>, fileIds: List<String>, spaceId: String?): SendResponse = callBody {
        http.post("$base/v1/send") { auth(); contentType(ContentType.Application.Json); setBody(SendRequest(to, tabs, fileIds, spaceId)) }
    }

    override suspend fun createSpace(name: String): Space = callBody {
        http.post("$base/v1/spaces") { auth(); contentType(ContentType.Application.Json); setBody(CreateSpaceRequest(name)) }
    }

    override suspend fun spaces(): List<Space> = callBody { http.get("$base/v1/spaces") { auth() } }

    override suspend fun renameSpace(id: String, name: String): Space = callBody {
        http.patch("$base/v1/spaces/$id") { auth(); contentType(ContentType.Application.Json); setBody(RenameSpaceRequest(name)) }
    }

    override suspend fun deleteSpace(id: String) { call { http.delete("$base/v1/spaces/$id") { auth() } } }

    private inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) { contentType(ContentType.Application.Json); setBody(body) }

    override suspend fun chats(spaceId: String): List<ChatSummary> = callBody { http.get("$base/v1/spaces/$spaceId/chats") { auth() } }

    override suspend fun createChat(spaceId: String, name: String): ChatSummary = callBody {
        http.post("$base/v1/spaces/$spaceId/chats") { auth(); jsonBody(CreateChatRequest(name)) }
    }

    override suspend fun renameChat(id: String, name: String): ChatSummary = callBody {
        http.patch("$base/v1/chats/$id") { auth(); jsonBody(RenameChatRequest(name)) }
    }

    override suspend fun deleteChat(id: String) { call { http.delete("$base/v1/chats/$id") { auth() } } }

    override suspend fun inviteToChat(chatId: String, deviceId: String): ChatInvite = callBody {
        http.post("$base/v1/chats/$chatId/invites") { auth(); jsonBody(InviteRequest(deviceId)) }
    }

    override suspend fun chatInvites(): List<ChatInvite> = callBody { http.get("$base/v1/chat-invites") { auth() } }

    override suspend fun approveInvite(id: String) { call { http.post("$base/v1/chat-invites/$id/approve") { auth() } } }

    override suspend fun denyInvite(id: String) { call { http.post("$base/v1/chat-invites/$id/deny") { auth() } } }

    override suspend fun joinInvite(id: String): ChatSummary = callBody { http.post("$base/v1/chat-invites/$id/join") { auth() } }

    override suspend fun declineInvite(id: String) { call { http.post("$base/v1/chat-invites/$id/decline") { auth() } } }

    override suspend fun transferChatOwner(chatId: String, deviceId: String): ChatSummary = callBody {
        http.post("$base/v1/chats/$chatId/owner") { auth(); jsonBody(TransferOwnerRequest(deviceId)) }
    }

    override suspend fun removeChatMember(chatId: String, deviceId: String) {
        call { http.delete("$base/v1/chats/$chatId/members/$deviceId") { auth() } }
    }

    override suspend fun adminJoinChat(chatId: String): ChatSummary = callBody { http.post("$base/v1/chats/$chatId/join") { auth() } }

    override suspend fun syncChat(chatId: String, sinceRev: Long): ChatSync = callBody {
        http.get("$base/v1/chats/$chatId/sync") { auth(); url.parameters.append("sinceRev", sinceRev.toString()) }
    }

    override suspend fun postMessage(chatId: String, text: String?, mentions: List<String>, attachments: List<Attachment>): ChatMessage = callBody {
        http.post("$base/v1/chats/$chatId/messages") { auth(); jsonBody(PostMessageRequest(text, mentions, attachments)) }
    }

    override suspend fun editMessage(chatId: String, msgId: String, text: String, mentions: List<String>): ChatMessage = callBody {
        http.patch("$base/v1/chats/$chatId/messages/$msgId") { auth(); jsonBody(EditMessageRequest(text, mentions)) }
    }

    override suspend fun deleteMessage(chatId: String, msgId: String) {
        call { http.delete("$base/v1/chats/$chatId/messages/$msgId") { auth() } }
    }

    override suspend fun markChatRead(chatId: String, rev: Long) {
        call { http.post("$base/v1/chats/$chatId/read") { auth(); jsonBody(ReadRequest(rev)) } }
    }

    override suspend fun markChatSeen(chatId: String, rev: Long) {
        call { http.post("$base/v1/chats/$chatId/seen") { auth(); jsonBody(SeenRequest(rev)) } }
    }

    override suspend fun messageReceipts(chatId: String, msgId: String): List<MessageReceipt> = callBody {
        http.get("$base/v1/chats/$chatId/messages/$msgId/receipts") { auth() }
    }

    override suspend fun openRoom(): RoomResponse = callBody { http.post("$base/v1/rooms") { auth() } }

    override suspend fun currentRoom(): RoomStatus? {
        val response = call(setOf(204)) { http.get("$base/v1/rooms/current") { auth() } }
        if (response.status.value == 204) return null
        return try {
            response.body<RoomStatus>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RelayException(RelayErrorKind.SERVER, "unreadable response: ${e.message}", e)
        }
    }

    override suspend fun endRoom(id: String) { call { http.delete("$base/v1/rooms/$id") { auth() } } }

    override suspend fun createInvite(spaceId: String): PairingCodeResponse = callBody { http.post("$base/v1/spaces/$spaceId/invites") { auth() } }

    override suspend fun removeMember(spaceId: String, deviceId: String) {
        call { http.delete("$base/v1/spaces/$spaceId/members/$deviceId") { auth() } }
    }

    override suspend fun transferAdmin(spaceId: String, deviceId: String): Space = callBody {
        http.post("$base/v1/spaces/$spaceId/admin") { auth(); contentType(ContentType.Application.Json); setBody(TransferAdminRequest(deviceId)) }
    }

    override suspend fun takeOverAdmin(spaceId: String): Space = callBody { http.post("$base/v1/spaces/$spaceId/takeover") { auth() } }

    override suspend fun inbox(): List<Delivery> = callBody { http.get("$base/v1/inbox") { auth() } }

    override suspend fun ack(deliveryId: String) { call { http.delete("$base/v1/inbox/$deliveryId") { auth() } } }

    override suspend fun createUpload(relativePath: String, mimeType: String, sizeBytes: Long): UploadStatus = callBody {
        http.post("$base/v1/uploads") {
            auth(); contentType(ContentType.Application.Json); setBody(CreateUploadRequest(relativePath, mimeType, sizeBytes))
        }
    }

    override suspend fun uploadStatus(uploadId: String): UploadStatus = callBody { http.get("$base/v1/uploads/$uploadId") { auth() } }

    override suspend fun uploadChunk(uploadId: String, start: Long, total: Long, bytes: ByteArray, length: Int): UploadStatus {
        require(length in 1..bytes.size) { "bad chunk length $length" }
        val body = object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Application.OctetStream
            override val contentLength = length.toLong()
            override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes, 0, length) }
        }
        // 409: the relay holds a different offset (or the upload already finished); its body is the current status.
        return callBody(okCodes = setOf(409)) {
            http.put("$base/v1/uploads/$uploadId") {
                auth()
                transferTimeout()
                header(HttpHeaders.ContentRange, "bytes $start-${start + length - 1}/$total")
                setBody(body)
            }
        }
    }

    override suspend fun downloadFile(ref: FileRef, target: File, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        target.absoluteFile.parentFile?.mkdirs()
        var have = if (target.isFile && target.length() <= ref.sizeBytes) target.length() else 0L
        if (have == 0L) target.delete()
        onProgress(have)
        if (have < ref.sizeBytes) {
            try {
                http.prepareGet("$base/v1/files/${ref.id}") {
                    auth()
                    transferTimeout()
                    if (have > 0) header(HttpHeaders.Range, "bytes=$have-")
                }.execute { response ->
                    when (response.status.value) {
                        206 -> {}
                        200 -> have = 0L // the relay sent the whole file: start over
                        else -> throw errorFor(response)
                    }
                    val channel = response.bodyAsChannel()
                    FileOutputStream(target, have > 0).use { out ->
                        val buf = ByteArray(BUFFER_BYTES)
                        while (have <= ref.sizeBytes) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n < 0) break
                            if (n == 0) continue
                            out.write(buf, 0, n)
                            have += n
                            onProgress(have)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                throw e
            } catch (e: Exception) {
                throw networkError(e) // the partial target stays for the next resume
            }
        }
        val actualSize = target.length()
        if (actualSize != ref.sizeBytes || !sha256HexOf(target).equals(ref.sha256, ignoreCase = true)) {
            target.delete()
            throw RelayException(RelayErrorKind.INVALID, "file ${ref.id} failed its size or SHA-256 check")
        }
    }

    override suspend fun downloadChatFile(fileId: String, target: File, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        target.absoluteFile.parentFile?.mkdirs()
        try {
            http.prepareGet("$base/v1/files/$fileId") { auth(); transferTimeout() }.execute { response ->
                if (response.status.value !in 200..299) throw errorFor(response)
                val channel = response.bodyAsChannel()
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(BUFFER_BYTES)
                    var have = 0L
                    onProgress(0)
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        have += n
                        onProgress(have)
                    }
                }
            }
        } catch (e: Throwable) {
            target.delete() // a partial chat file is never resumed
            throw if (e is Exception && e !is CancellationException && e !is RelayException) networkError(e) else e
        }
    }

    override fun events(): Flow<RelayEvent> = channelFlow {
        val inner = launch { connectLoop().collect { send(it) } }
        val watcher = launch { closed.join(); inner.cancel() }
        inner.join()
        watcher.cancel()
    }

    private fun connectLoop(): Flow<RelayEvent> = flow {
        val wsUrl = (if (base.startsWith("https", ignoreCase = true)) "wss" else "ws") + "://" + base.substringAfter("://") + "/v1/ws"
        var attempt = 0
        while (true) {
            var connected = false
            var connectedAt = 0L
            try {
                http.webSocket(wsUrl, request = {
                    auth()
                    timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                }) {
                    connected = true
                    connectedAt = System.nanoTime()
                    emit(RelayEvent.Connected)
                    while (true) {
                        // Read-idle watchdog: a half-open socket never ends `incoming`, so give up and reconnect.
                        val result = withTimeoutOrNull(idleTimeoutMs) { incoming.receiveCatching() } ?: break
                        val frame = result.getOrNull() ?: break
                        if (frame is Frame.Text) {
                            val event = try {
                                val text = frame.readText()
                                if (isHeartbeat(text)) null else MjolnirJson.decodeFromString(RelayEvent.serializer(), text)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            if (event != null) emit(event)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!connected) {
                    try {
                        me()
                    } catch (e: RelayException) {
                        if (e.kind == RelayErrorKind.UNAUTHORIZED) throw e
                    }
                }
            }
            if (connected) emit(RelayEvent.Disconnected)
            if (connected && (System.nanoTime() - connectedAt) / 1_000_000 >= stableAfterMs) attempt = 0
            delay(reconnectDelayMs(attempt))
            attempt++
        }
    }
}
