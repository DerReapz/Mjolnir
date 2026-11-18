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
import kotlinx.coroutines.CancellationException
import java.io.IOException

fun HttpClientConfig<*>.installMjolnirDefaults() {
    install(ContentNegotiation) { json(MjolnirJson) }
    install(WebSockets)
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
    }
}

class RelayClient(
    private val http: HttpClient,
    baseUrl: String,
    private val token: () -> String?,
) : RelayApi {
    private val base = baseUrl.trimEnd('/')

    companion object {
        fun create(baseUrl: String, token: () -> String?): RelayClient =
            RelayClient(HttpClient(OkHttp) { installMjolnirDefaults() }, baseUrl, token)
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
}
