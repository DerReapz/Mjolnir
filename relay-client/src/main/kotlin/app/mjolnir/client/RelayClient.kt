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
import app.mjolnir.protocol.MuteRequest
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
import app.mjolnir.protocol.CreateVoiceRoomRequest
import app.mjolnir.protocol.VoiceRoom
import app.mjolnir.protocol.VoiceRoster
import app.mjolnir.client.voice.VoiceLink
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
import io.ktor.client.request.HttpRequestPipeline
import app.mjolnir.protocol.APP_VERSION_HEADER
import app.mjolnir.protocol.RELAY_VERSION_HEADER
import app.mjolnir.protocol.Versions
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import okhttp3.OkHttpClient
import kotlin.math.min
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun HttpClientConfig<*>.installMjolnirDefaults(pingIntervalMillis: Long = DEFAULT_PING_SECONDS * 1_000) {
    install(ContentNegotiation) { json(MjolnirJson) }
    install(WebSockets) { this.pingIntervalMillis = pingIntervalMillis }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
    }
}

/** WebSocket ping interval of the relay client: a socket whose pong does not come back within it fails. */
const val DEFAULT_PING_SECONDS = 20L

/**
 * Voice sockets ping faster (Ruling V6): a dead voice link must be noticed well within the relay's 10 s grace so a
 * reconnect takes the slot back silently, and a quiet room sends no frames a read watchdog could use.
 */
const val VOICE_PING_SECONDS = 5L

/**
 * The Ktor client every relay call uses: OkHttp engine plus [installMjolnirDefaults], with WebSocket pings every
 * [pingSeconds] ([VOICE_PING_SECONDS] for the voice client).
 */
fun newMjolnirHttpClient(pingSeconds: Long = DEFAULT_PING_SECONDS): HttpClient = HttpClient(OkHttp) {
    installMjolnirDefaults(pingSeconds * 1_000)
    engine { preconfigured = OkHttpClient.Builder().pingInterval(pingSeconds, TimeUnit.SECONDS).build() }
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

/** The close code of every voice socket close the relay makes; its reason text says why. */
private const val VOICE_CLOSE_CODE: Short = 4000
/** [VoiceLink.closeReason] when the relay has no voice socket (an older relay: a 404 for the upgrade). */
const val VOICE_RELAY_OUTDATED = "relay_outdated"
/** [VoiceLink.closeReason] when the relay needs a newer app (426 for the upgrade). */
const val VOICE_APP_OUTDATED = "app_outdated"
private const val VOICE_SEND_QUEUE = 50
/** A space id no relay has: the voice-room list for it tells a voice-capable relay (its own 404) from an old one. */
private const val VOICE_PROBE_SPACE = "voice-probe"

private const val VOICE_CLOSE_WAIT_MS = 3_000L

internal fun backoffMs(attempt: Int): Long = min(30_000L, 1_000L shl attempt.coerceIn(0, 20))

class RelayClient(
    private val http: HttpClient,
    baseUrl: String,
    /** This app's release version, `1.0.N`; sent as `Mjolnir-App-Version` on every request and on the WebSocket. */
    private val appVersion: String,
    private val reconnectDelayMs: (Int) -> Long = ::backoffMs,
    private val idleTimeoutMs: Long = 75_000,
    private val stableAfterMs: Long = 30_000,
    /** Socket (idle read/write) timeout for chunk uploads and downloads, which have no overall deadline; null = none. */
    private val transferSocketTimeoutMs: Long? = TRANSFER_SOCKET_TIMEOUT_MS,
    /**
     * The client voice sockets use: [newMjolnirHttpClient] with [VOICE_PING_SECONDS] in production. It carries the same
     * headers as [http] (see [installRelayInterceptors]). Defaults to [http] (tests).
     */
    internal val voiceHttp: HttpClient = http,
    private val token: () -> String?,
) : RelayApi, AutoCloseable {
    private val base = baseUrl.trimEnd('/')
    private val closed = Job()
    private val _relayVersion = MutableStateFlow<String?>(null)

    /**
     * The relay's `Mjolnir-Relay-Version` from the latest response that carried it (any status, the WebSocket handshake
     * included); null until one has. The apps forward it to [Updater.onRelayVersion].
     */
    val relayVersion: StateFlow<String?> = _relayVersion.asStateFlow()

    init {
        installRelayInterceptors(http)
        if (voiceHttp !== http) installRelayInterceptors(voiceHttp)
    }

    /**
     * What every request of this client carries and reads, on whichever HttpClient sends it: the app's version header
     * out, the relay's version back. The bearer is added per request by `auth()`.
     */
    private fun installRelayInterceptors(client: HttpClient) {
        client.requestPipeline.intercept(HttpRequestPipeline.State) { context.headers[APP_VERSION_HEADER] = appVersion }
        client.plugin(HttpSend).intercept { request ->
            execute(request).also { call ->
                call.response.headers[RELAY_VERSION_HEADER]?.let { _relayVersion.value = it }
            }
        }
    }

    /** Where open voice links run; cancelled by [close]. */
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Closes the HTTP client and ends any active [events] flow and voice link cleanly. */
    override fun close() {
        closed.complete()
        voiceScope.cancel()
        http.close()
        if (voiceHttp !== http) voiceHttp.close()
    }

    companion object {
        private val UNMARKED_KINDS = setOf(RelayErrorKind.UNAUTHORIZED, RelayErrorKind.RATE_LIMITED, RelayErrorKind.NOT_FOUND, RelayErrorKind.APP_OUTDATED)

        fun create(baseUrl: String, appVersion: String, token: () -> String?): RelayClient =
            RelayClient(
                newMjolnirHttpClient(),
                baseUrl,
                appVersion,
                voiceHttp = newMjolnirHttpClient(VOICE_PING_SECONDS),
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
            // The relay answers every 404 of its own with an ErrorResponse; a bare one is a route an older relay lacks.
            404 -> if (parsed == null) RelayErrorKind.RELAY_OUTDATED else RelayErrorKind.NOT_FOUND
            409 -> RelayErrorKind.ALREADY_SENT
            413 -> RelayErrorKind.TOO_LARGE
            // A relay that no longer serves this app (the accounts relay's update_required), whatever the body says.
            426 -> RelayErrorKind.APP_OUTDATED
            429 -> RelayErrorKind.RATE_LIMITED
            507 -> RelayErrorKind.STORAGE_FULL
            else -> if (code >= 500) RelayErrorKind.SERVER else RelayErrorKind.INVALID
        }
        // Answers whose copy stays specific whatever the relay's age (reset, rate limit, the relay's own not-found) aren't marked.
        val olderRelay = kind !in UNMARKED_KINDS &&
            response.headers[RELAY_VERSION_HEADER]?.let { Versions.isNewer(appVersion, it) } == true
        return RelayException(kind, message, detail = parsed?.detail, fromOlderRelay = olderRelay)
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

    override suspend fun createChannel(spaceId: String, name: String): ChatSummary = callBody {
        http.post("$base/v1/spaces/$spaceId/channels") { auth(); jsonBody(CreateChatRequest(name)) }
    }

    override suspend fun setChatMuted(chatId: String, muted: Boolean): ChatSummary = callBody {
        http.put("$base/v1/chats/$chatId/mute") { auth(); jsonBody(MuteRequest(muted)) }
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

    override suspend fun voiceRooms(spaceId: String): List<VoiceRoom> = callBody { http.get("$base/v1/spaces/$spaceId/voice-rooms") { auth() } }

    override suspend fun createVoiceRoom(spaceId: String, name: String): VoiceRoom = callBody {
        http.post("$base/v1/spaces/$spaceId/voice-rooms") { auth(); jsonBody(CreateVoiceRoomRequest(name)) }
    }

    override suspend fun renameVoiceRoom(id: String, name: String): VoiceRoom = callBody {
        http.patch("$base/v1/voice-rooms/$id") { auth(); jsonBody(CreateVoiceRoomRequest(name)) }
    }

    override suspend fun deleteVoiceRoom(id: String) { call { http.delete("$base/v1/voice-rooms/$id") { auth() } } }

    override fun openVoice(roomId: String, onRoster: (List<String>) -> Unit, onFrame: (speakerIndex: Int, seq: Int, opus: ByteArray) -> Unit): VoiceLink {
        // About a second of audio; when the socket can't keep up the oldest frames go (stale audio is worthless).
        val outgoing = Channel<ByteArray>(VOICE_SEND_QUEUE, BufferOverflow.DROP_OLDEST)
        val leave = CompletableDeferred<Unit>()
        val reason = CompletableDeferred<String?>()
        val job = voiceScope.launch {
            var relayReason: String? = null
            var opened = false
            try {
                voiceHttp.webSocket(wsUrl("/v1/voice/$roomId"), request = {
                    auth()
                    timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                }) {
                    opened = true
                    val writer = launch { for (frame in outgoing) send(Frame.Binary(true, frame)) }
                    val leaver = launch {
                        leave.await()
                        writer.cancel()
                        close(CloseReason(CloseReason.Codes.NORMAL, "leave"))
                    }
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> voiceRoster(frame.readText())?.let(onRoster)
                                is Frame.Binary -> {
                                    val data = frame.readBytes()
                                    if (data.size >= 4) {
                                        val seq = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                                        onFrame(data[0].toInt() and 0xFF, seq, data.copyOfRange(3, data.size))
                                    }
                                }
                                else -> {}
                            }
                        }
                    } finally {
                        writer.cancel(); leaver.cancel()
                    }
                    val why = withTimeoutOrNull(1_000) { runCatching { closeReason.await() }.getOrNull() }
                    if (why?.code == VOICE_CLOSE_CODE) relayReason = why.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A dropped link or a failed connect: no relay reason, unless the relay refused the upgrade itself.
                if (!opened) relayReason = refusedVoiceReason()
            } finally {
                reason.complete(relayReason)
            }
        }
        // A link opened after [close] never runs its body: it still ends.
        job.invokeOnCompletion { reason.complete(null) }
        return object : VoiceLink {
            override fun send(seq: Int, opus: ByteArray) {
                val frame = ByteArray(opus.size + 2)
                frame[0] = (seq shr 8).toByte(); frame[1] = seq.toByte()
                opus.copyInto(frame, 2)
                outgoing.trySend(frame)
            }

            override fun close() {
                if (!leave.complete(Unit)) return
                // The close handshake normally ends the link; one that hangs is cut.
                voiceScope.launch { if (withTimeoutOrNull(VOICE_CLOSE_WAIT_MS) { job.join() } == null) job.cancel() }
            }

            override val closeReason: Deferred<String?> = reason
        }
    }

    /**
     * Why a voice socket that never opened won't open on a retry, or null when a retry may work. The engines don't
     * report the upgrade's HTTP status, so a voice route every voice-capable relay has is asked instead: a rejected
     * device (401/403) reads as `not_found`, a relay without voice rooms (a bare 404) as [VOICE_RELAY_OUTDATED], a relay
     * that needs a newer app (426) as [VOICE_APP_OUTDATED]. Its own not-found answer (the probe names no real space)
     * or a network error mean: try again.
     */
    private suspend fun refusedVoiceReason(): String? = try {
        voiceRooms(VOICE_PROBE_SPACE)
        null
    } catch (e: RelayException) {
        when (e.kind) {
            RelayErrorKind.UNAUTHORIZED, RelayErrorKind.NOT_PAIRED -> "not_found"
            RelayErrorKind.RELAY_OUTDATED -> VOICE_RELAY_OUTDATED
            RelayErrorKind.APP_OUTDATED -> VOICE_APP_OUTDATED
            else -> null
        }
    }

    private fun voiceRoster(text: String): List<String>? = try {
        MjolnirJson.decodeFromString(VoiceRoster.serializer(), text).takeIf { it.type == "roster" }?.participants
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
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

    /** The headers this client's own requests carry: the app's version and, with a token, the bearer. */
    override fun chatFileRequest(fileId: String): FileRequest =
        FileRequest("$base/v1/files/$fileId", mapOf(APP_VERSION_HEADER to appVersion) + token()?.let { mapOf(HttpHeaders.Authorization to "Bearer $it") }.orEmpty())

    override fun events(): Flow<RelayEvent> = channelFlow {
        val inner = launch { connectLoop().collect { send(it) } }
        val watcher = launch { closed.join(); inner.cancel() }
        inner.join()
        watcher.cancel()
    }

    /** The WebSocket URL of [path] on the relay (`wss` for an `https` relay). */
    private fun wsUrl(path: String): String =
        (if (base.startsWith("https", ignoreCase = true)) "wss" else "ws") + "://" + base.substringAfter("://") + path

    private fun connectLoop(): Flow<RelayEvent> = flow {
        val wsUrl = wsUrl("/v1/ws")
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
