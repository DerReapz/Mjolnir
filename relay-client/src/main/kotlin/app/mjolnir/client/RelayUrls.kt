package app.mjolnir.client

import java.net.URI

object RelayUrls {
    private val LOCAL_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    /** Trimmed, no trailing "/"; null unless https, or http on a local dev host. */
    fun normalize(input: String): String? {
        val s = input.trim().trimEnd('/')
        val uri = try { URI(s) } catch (_: Exception) { return null }
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        return when (uri.scheme?.lowercase()) {
            "https" -> s
            "http" -> if (host.lowercase() in LOCAL_HTTP_HOSTS) s else null
            else -> null
        }
    }
}
