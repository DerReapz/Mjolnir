package app.mjolnir.android.throwing

import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
enum class Sensitivity(val thresholdMs2: Float) { LOW(22f), MEDIUM(16f), HIGH(11f) }

/**
 * Detects a flick while the user holds a finger on the throw target. Feed it gravity-free
 * acceleration samples; [onSample] returns true exactly once per hold.
 */
class ThrowDetector(
    var sensitivity: Sensitivity = Sensitivity.MEDIUM,
    private val armDelayNanos: Long = 150_000_000L,
) {
    private var held = false
    private var holdStartNanos = 0L
    private var overCount = 0
    private var fired = false

    fun onHoldStart(timestampNanos: Long) {
        held = true
        holdStartNanos = timestampNanos
        overCount = 0
        fired = false
    }

    fun onSample(timestampNanos: Long, x: Float, y: Float, z: Float): Boolean {
        if (!held || fired) return false
        if (timestampNanos - holdStartNanos < armDelayNanos) return false
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude >= sensitivity.thresholdMs2) overCount++ else overCount = 0
        if (overCount >= 2) {
            fired = true
            return true
        }
        return false
    }

    fun onHoldEnd() {
        held = false
        overCount = 0
        fired = false
    }
}
