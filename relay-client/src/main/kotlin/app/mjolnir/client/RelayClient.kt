package app.mjolnir.client

import app.mjolnir.protocol.ClaimRequest
import app.mjolnir.protocol.ClaimResponse
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
import kotlin.math.min

fun HttpClientConfig<*>.installMjolnirDefaults() {
    install(ContentNegotiation) { json(MjolnirJson) }
    install(WebSockets) { pingIntervalMillis = 20_000 }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
    }
}

internal fun backoffMs(attempt: Int): Long = min(30_000L, 1_000L shl attempt.coerceIn(0, 20))

class RelayClient(
    private val http: HttpClient,
    baseUrl: String,
    private val reconnectDelayMs: (Int) -> Long = ::backoffMs,
    private val idleTimeoutMs: Long = 75_000,
    private val stableAfterMs: Long = 30_000,
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
            RelayClient(HttpClient(OkHttp) { installMjolnirDefaults() }, baseUrl, token = token)
    }

    private fun HttpRequestBuilder.auth() { token()?.let { bearerAuth(it) } }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw RelayException(RelayErrorKind.NETWORK, e.message ?: "network error", e)
        } catch (e: Exception) {
            // Ktor timeout exceptions and engine failures that are not IOExceptions.
            throw RelayException(RelayErrorKind.NETWORK, e.message ?: "network error", e)
        }
        val code = response.status.value
        if (code in 200..299) return response
        val message = try {
            MjolnirJson.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()).error
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "HTTP $code"
        }
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
        throw RelayException(kind, message)
    }

    private suspend inline fun <reified T> callBody(noinline block: suspend () -> HttpResponse): T {
        val response = call(block)
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

    override suspend fun claimPairingCode(code: String): ClaimResponse = callBody {
        http.post("$base/v1/pairings/claim") { auth(); contentType(ContentType.Application.Json); setBody(ClaimRequest(code)) }
    }

    override suspend fun pairingStatus(pairingId: String): PairingStatus = callBody { http.get("$base/v1/pairings/$pairingId") { auth() } }

    override suspend fun acceptPairing(pairingId: String) { call { http.post("$base/v1/pairings/$pairingId/accept") { auth() } } }

    override suspend fun rejectPairing(pairingId: String) { call { http.post("$base/v1/pairings/$pairingId/reject") { auth() } } }

    override suspend fun peers(): List<Peer> = callBody { http.get("$base/v1/peers") { auth() } }

    override suspend fun unpair(deviceId: String) { call { http.delete("$base/v1/peers/$deviceId") { auth() } } }

    override suspend fun send(to: String, tabs: List<Tab>, fileIds: List<String>): SendResponse = callBody {
        http.post("$base/v1/send") { auth(); contentType(ContentType.Application.Json); setBody(SendRequest(to, tabs, fileIds)) }
    }

    override suspend fun inbox(): List<Delivery> = callBody { http.get("$base/v1/inbox") { auth() } }

    override suspend fun ack(deliveryId: String) { call { http.delete("$base/v1/inbox/$deliveryId") { auth() } } }

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
                                MjolnirJson.decodeFromString(RelayEvent.serializer(), frame.readText())
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
            if (connected && (System.nanoTime() - connectedAt) / 1_000_000 >= stableAfterMs) attempt = 0
            delay(reconnectDelayMs(attempt))
            attempt++
        }
    }
}
