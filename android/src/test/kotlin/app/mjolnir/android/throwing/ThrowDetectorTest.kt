package app.mjolnir.android.throwing

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

private fun ThrowDetector.feed(ms: Long, mag: Float) = onSample(ms * 1_000_000, mag, 0f, 0f)

class ThrowDetectorTest {
    @Test fun ignoresSamplesWhenNotHeld() { val d = ThrowDetector(); assertFalse(d.feed(500, 30f)); assertFalse(d.feed(520, 30f)) }
    @Test fun ignoresThumbJoltDuringArmDelay() { val d = ThrowDetector(); d.onHoldStart(0)
        assertFalse(d.feed(100, 30f)); assertFalse(d.feed(120, 30f)); assertFalse(d.feed(140, 30f)) }
    @Test fun singleSpikeIsNotAThrow() { val d = ThrowDetector(); d.onHoldStart(0)
        assertFalse(d.feed(200, 30f)); assertFalse(d.feed(220, 2f)); assertFalse(d.feed(240, 30f)) }
    @Test fun twoConsecutiveSamplesOverThresholdThrow() { val d = ThrowDetector(); d.onHoldStart(0)
        assertFalse(d.feed(200, 17f)); assertTrue(d.feed(220, 17f)) }
    @Test fun throwsOncePerHold() { val d = ThrowDetector(); d.onHoldStart(0); d.feed(200, 17f); d.feed(220, 17f)
        assertFalse(d.feed(240, 30f)); assertFalse(d.feed(260, 30f)) }
    @Test fun releaseResetsAndNextHoldCanThrow() { val d = ThrowDetector(); d.onHoldStart(0); d.feed(200, 17f); d.onHoldEnd()
        assertFalse(d.feed(220, 17f)); d.onHoldStart(1_000_000_000); assertFalse(d.feed(1_200, 17f)); assertTrue(d.feed(1_220, 17f)) }
    @Test fun sensitivitySetsThreshold() {
        fun throws(s: Sensitivity, mag: Float) = ThrowDetector(s).run { onHoldStart(0); feed(200, mag); feed(220, mag) }
        assertTrue(throws(Sensitivity.HIGH, 12f)); assertFalse(throws(Sensitivity.MEDIUM, 12f))
        assertTrue(throws(Sensitivity.MEDIUM, 20f)); assertFalse(throws(Sensitivity.LOW, 20f))
    }
    @Test fun usesVectorMagnitude() { val d = ThrowDetector(); d.onHoldStart(0)
        assertFalse(d.onSample(200_000_000, 10f, 10f, 10f)); assertTrue(d.onSample(220_000_000, 10f, 10f, 10f)) }
}
