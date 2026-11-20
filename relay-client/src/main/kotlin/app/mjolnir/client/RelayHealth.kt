package app.mjolnir.client

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

sealed interface RelayCheck {
    data class Ok(val millis: Long) : RelayCheck
    data class Failed(val message: String) : RelayCheck
}

/** `GET {url}/health` must answer `ok` within [timeoutMs]. Pass [client] to reuse one; otherwise a default one is made and closed. */
suspend fun checkRelay(url: String, timeoutMs: Long = 5_000, client: HttpClient? = null): RelayCheck {
    val base = RelayUrls.normalize(url) ?: return RelayCheck.Failed("Not a valid relay address")
    val http = client ?: newMjolnirHttpClient()
    return try {
        val start = System.nanoTime()
        val result = withTimeout(timeoutMs) {
            val response = http.get("$base/health")
            when {
                !response.status.isSuccess() -> RelayCheck.Failed("Relay answered ${response.status.value}")
                response.bodyAsText().trim() != "ok" -> RelayCheck.Failed("Unexpected response from relay")
                else -> RelayCheck.Ok((System.nanoTime() - start) / 1_000_000)
            }
        }
        result
    } catch (e: TimeoutCancellationException) {
        RelayCheck.Failed("Timed out")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RelayCheck.Failed(e.message ?: e.javaClass.simpleName)
    } finally {
        if (client == null) http.close()
    }
}
