package app.mjolnir.client.voice

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException
import io.github.jaredmdobson.concentus.OpusSignal

/**
 * Opus at 48 kHz mono in 20 ms frames, with in-band FEC. One instance per direction and peer: the encoder is the
 * capture side's, the decoder one speaker's (each is created on first use). Not thread-safe.
 */
class OpusCodec(private val bitrate: Int = BITRATE) {
    private val encoder by lazy {
        OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            setBitrate(this@OpusCodec.bitrate)
            setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
            setUseInbandFEC(true)
            setPacketLossPercent(EXPECTED_LOSS_PERCENT)
            setComplexity(COMPLEXITY)
        }
    }
    private val decoder by lazy { OpusDecoder(SAMPLE_RATE, 1) }
    private val packet = ByteArray(MAX_PACKET_BYTES)

    /** Encodes one frame of [FRAME_SAMPLES] samples into one packet. */
    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == FRAME_SAMPLES) { "a frame is $FRAME_SAMPLES samples, got ${pcm.size}" }
        val n = encoder.encode(pcm, 0, FRAME_SAMPLES, packet, 0, packet.size)
        return packet.copyOf(n)
    }

    /**
     * Decodes one packet into a frame. A lost frame ([packet] null) is concealed, or rebuilt from the in-band FEC of
     * [next] when the packet after it is already here. A packet that does not decode is concealed too.
     */
    fun decode(packet: ByteArray?, next: ByteArray? = null): ShortArray {
        val out = ShortArray(FRAME_SAMPLES)
        try {
            when {
                packet != null -> decoder.decode(packet, 0, packet.size, out, 0, FRAME_SAMPLES, false)
                next != null -> decoder.decode(next, 0, next.size, out, 0, FRAME_SAMPLES, true)
                else -> decoder.decode(null, 0, 0, out, 0, FRAME_SAMPLES, false)
            }
        } catch (e: OpusException) {
            return conceal()
        } catch (e: RuntimeException) { // Concentus can throw index errors on garbage
            return conceal()
        }
        return out
    }

    private fun conceal(): ShortArray {
        val out = ShortArray(FRAME_SAMPLES)
        try {
            decoder.decode(null, 0, 0, out, 0, FRAME_SAMPLES, false)
        } catch (e: Exception) {
            out.fill(0)
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS
        /** RFC 6716's largest packet; the relay refuses bigger ones. */
        const val MAX_PACKET_BYTES = 1275
        const val BITRATE = 28_000
        private const val EXPECTED_LOSS_PERCENT = 10
        private const val COMPLEXITY = 5
    }
}
