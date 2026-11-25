package app.mjolnir.client.voice

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixerTest {
    @Test fun twoLoudSignalsDoNotWrapAround() {
        val loud = ShortArray(960) { 30_000 }
        val loudNeg = ShortArray(960) { (-30_000).toShort() }
        val up = Mixer.mix(listOf(loud, loud))
        val down = Mixer.mix(listOf(loudNeg, loudNeg))
        assertTrue(up.all { it > 30_000 }, "a positive sum stays positive and near full scale")
        assertTrue(down.all { it < -30_000 })
        // Soft: louder input is never quieter output.
        val louder = Mixer.mix(listOf(loud, loud, loud))
        assertTrue(louder[0] >= up[0])
    }

    @Test fun silencePlusSignalIsTheSignal() {
        val signal = Signals.tone(3)
        assertContentEquals(signal, Mixer.mix(listOf(Signals.silence(), signal)))
    }

    @Test fun nothingIsAFrameOfSilence() {
        val out = Mixer.mix(emptyList())
        assertEquals(960, out.size)
        assertTrue(out.all { it == 0.toShort() })
    }
}
