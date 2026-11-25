package app.mjolnir.client.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeakingDetectorTest {
    @Test fun silenceIsNotSpeaking() {
        val d = SpeakingDetector()
        repeat(20) { assertFalse(d.update(Signals.silence())) }
    }

    @Test fun toneLongerThan100msIsSpeaking() {
        val d = SpeakingDetector()
        val results = List(7) { d.update(Signals.tone(it)) }
        assertFalse(results.take(4).any { it }, "under 100 ms: $results")
        assertTrue(results.last(), "140 ms of tone: $results")
    }

    @Test fun blipOf40msIsNot() {
        val d = SpeakingDetector()
        val results = List(2) { d.update(Signals.tone(it)) } + List(10) { d.update(Signals.silence()) }
        assertFalse(results.any { it }, "$results")
    }

    @Test fun quietToneBelowThresholdIsNot() {
        val d = SpeakingDetector()
        // About -60 dBFS.
        repeat(20) { assertFalse(d.update(Signals.tone(it, amplitude = 30))) }
    }
}
