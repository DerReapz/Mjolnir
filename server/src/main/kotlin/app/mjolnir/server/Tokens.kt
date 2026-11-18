package app.mjolnir.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

private val secureRandom = SecureRandom()

fun newId(): String = UUID.randomUUID().toString()

fun newToken(): String {
    val bytes = ByteArray(32).also { secureRandom.nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
