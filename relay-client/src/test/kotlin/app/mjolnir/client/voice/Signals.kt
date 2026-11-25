package app.mjolnir.client.voice

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Test signals: 20 ms frames at 48 kHz. */
object Signals {
    const val FRAME = 960

    /** Frame [index] of a continuous [hz] sine at [amplitude]. */
    fun tone(index: Int, hz: Double = 1_000.0, amplitude: Int = 8_000): ShortArray =
        ShortArray(FRAME) { i -> (amplitude * sin(2 * PI * hz * (index * FRAME + i) / 48_000.0)).toInt().toShort() }

    fun silence(): ShortArray = ShortArray(FRAME)

    fun rms(pcm: ShortArray): Double = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size.coerceAtLeast(1))

    fun db(ratio: Double): Double = 20 * log10(ratio)
}
