package app.mjolnir.client.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpusCodecTest {
    @Test fun sineRoundTripKeepsItsEnergyInSmallPackets() {
        val tx = OpusCodec(); val rx = OpusCodec()
        val inRms = mutableListOf<Double>(); val outRms = mutableListOf<Double>()
        for (i in 0 until 50) {
            val pcm = Signals.tone(i)
            val packet = tx.encode(pcm)
            assertTrue(packet.size in 1 until 200, "packet of ${packet.size} bytes")
            val out = rx.decode(packet)
            assertEquals(OpusCodec.FRAME_SAMPLES, out.size)
            if (i >= 10) { inRms += Signals.rms(pcm); outRms += Signals.rms(out) } // past the codec's warm-up
        }
        val diffDb = Signals.db(outRms.average() / inRms.average())
        assertTrue(abs(diffDb) <= 3.0, "energy changed by $diffDb dB")
    }

    @Test fun lostFrameIsConcealedAtFullLength() {
        val tx = OpusCodec(); val rx = OpusCodec()
        repeat(5) { rx.decode(tx.encode(Signals.tone(it))) }
        assertEquals(OpusCodec.FRAME_SAMPLES, rx.decode(null).size)
        // With the next packet known, the lost frame comes from its in-band FEC (still a full frame).
        val next = tx.encode(Signals.tone(6))
        assertEquals(OpusCodec.FRAME_SAMPLES, rx.decode(null, next).size)
        assertEquals(OpusCodec.FRAME_SAMPLES, OpusCodec().decode(null).size)
    }

    @Test fun garbageDecodesToAFullFrame() {
        assertEquals(OpusCodec.FRAME_SAMPLES, OpusCodec().decode(byteArrayOf(-1, -1, -1, -1, -1)).size)
    }
}
