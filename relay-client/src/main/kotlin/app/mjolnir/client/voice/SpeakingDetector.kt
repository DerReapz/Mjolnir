package app.mjolnir.client.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Says whether a stream of frames is speech: the level has stayed above [thresholdDb] (dBFS) for at least [holdMs].
 * Once speaking it stays so through pauses shorter than [releaseMs], so words are not cut apart. Not thread-safe.
 */
class SpeakingDetector(
    private val thresholdDb: Double = -45.0,
    private val holdMs: Int = 100,
    private val releaseMs: Int = 300,
) {
    private var aboveMs = 0
    private var belowMs = 0
    private var speaking = false

    /** Feeds the next frame; returns whether this frame counts as speech. */
    fun update(pcm: ShortArray): Boolean {
        val frameMs = pcm.size * 1000 / OpusCodec.SAMPLE_RATE
        if (levelDb(pcm) > thresholdDb) {
            aboveMs += frameMs; belowMs = 0
            if (aboveMs >= holdMs) speaking = true
        } else {
            aboveMs = 0; belowMs += frameMs
            if (belowMs >= releaseMs) speaking = false
        }
        return speaking
    }

    fun reset() { aboveMs = 0; belowMs = 0; speaking = false }

    companion object {
        /** RMS level of [pcm] in dBFS (-inf for silence). */
        fun levelDb(pcm: ShortArray): Double {
            if (pcm.isEmpty()) return Double.NEGATIVE_INFINITY
            var sum = 0.0
            for (s in pcm) sum += s.toDouble() * s
            return 20 * log10(sqrt(sum / pcm.size) / 32_768.0)
        }
    }
}
