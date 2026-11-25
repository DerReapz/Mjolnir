package app.mjolnir.client.voice

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

/** Mixes the frames of several speakers into one. */
object Mixer {
    /** Below this a sample passes unchanged; above it the sum is bent smoothly towards full scale instead of wrapping. */
    private const val KNEE = 24_576
    private const val HEADROOM = Short.MAX_VALUE - KNEE

    /** The sum of [frames], soft-clipped; one frame of silence when there are none. */
    fun mix(frames: List<ShortArray>): ShortArray {
        val size = frames.maxOfOrNull { it.size } ?: OpusCodec.FRAME_SAMPLES
        val out = ShortArray(size)
        for (i in 0 until size) {
            var sum = 0
            for (f in frames) if (i < f.size) sum += f[i]
            out[i] = softClip(sum)
        }
        return out
    }

    private fun softClip(x: Int): Short {
        val m = abs(x)
        if (m <= KNEE) return x.toShort()
        val bent = KNEE + HEADROOM * tanh((m - KNEE).toDouble() / HEADROOM)
        return (sign(x.toDouble()) * bent).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
