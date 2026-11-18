package app.mjolnir.protocol

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

object PairingCode {
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 6

    fun generate(random: Random = SecureRandom().asKotlinRandom()): String =
        buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun normalize(input: String): String? {
        val s = input.uppercase().filter { it != ' ' && it != '-' }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }
            .joinToString("")
        return s.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    fun display(code: String): String = code.take(3) + "-" + code.drop(3)
}
