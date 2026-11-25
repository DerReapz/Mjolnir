package app.mjolnir.desktop.voice

import app.mjolnir.client.voice.AudioInput
import app.mjolnir.client.voice.AudioOutput
import app.mjolnir.client.voice.VoiceAudioFactory
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** 48 kHz, 16-bit, mono, signed, little-endian: what the voice engine reads and writes. */
internal val VOICE_FORMAT = AudioFormat(48_000f, 16, 1, true, false)
private const val FRAME_SAMPLES = 960
private const val FRAME_BYTES = FRAME_SAMPLES * 2

/** The default microphone and speaker, opened afresh for each voice session. Opening fails in `start()`, which ends the session as an audio error. */
class DesktopAudioFactory : VoiceAudioFactory {
    override fun input(): AudioInput = DesktopAudioInput()
    override fun output(): AudioOutput = DesktopAudioOutput()
}

/** The threads capture and playout block on: two per session (one capturing, one playing), room for a session handover. */
fun newAudioDispatcher(): java.util.concurrent.ExecutorService = Executors.newFixedThreadPool(4, ThreadFactory { r ->
    Thread(r, "mjolnir-voice-audio").apply { isDaemon = true }
})

internal fun pcmToBytes(pcm: ShortArray, into: ByteArray = ByteArray(pcm.size * 2)): ByteArray {
    for (i in pcm.indices) {
        into[2 * i] = pcm[i].toInt().toByte()
        into[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
    }
    return into
}

internal fun bytesToPcm(bytes: ByteArray, count: Int, into: ShortArray) {
    for (i in 0 until count / 2) into[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
}

private class DesktopAudioInput : AudioInput {
    private val bytes = ByteArray(FRAME_BYTES)
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var stopped = false

    override fun start() {
        val l = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, VOICE_FORMAT)) as TargetDataLine
        l.open(VOICE_FORMAT, FRAME_BYTES * 8)
        l.start()
        line = l
        if (stopped) stop()
    }

    override fun read(into: ShortArray): Int {
        val l = line ?: return -1
        var got = 0
        while (got < FRAME_BYTES) {
            if (stopped) return -1
            val n = l.read(bytes, got, FRAME_BYTES - got)
            if (n <= 0) return -1 // a closed line reads nothing
            got += n
        }
        bytesToPcm(bytes, got, into)
        return got / 2
    }

    override fun stop() {
        stopped = true
        line?.let { runCatching { it.stop() }; runCatching { it.close() } }
    }
}

private class DesktopAudioOutput : AudioOutput {
    private val bytes = ByteArray(FRAME_BYTES)
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var stopped = false

    override fun start() {
        val l = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, VOICE_FORMAT)) as SourceDataLine
        l.open(VOICE_FORMAT, FRAME_BYTES * 6) // about 120 ms: room for jitter, little added delay
        l.start()
        line = l
        if (stopped) stop()
    }

    override fun write(pcm: ShortArray) {
        val l = line ?: return
        if (stopped) return
        pcmToBytes(pcm, bytes)
        l.write(bytes, 0, minOf(bytes.size, pcm.size * 2))
    }

    override fun stop() {
        stopped = true
        line?.let { runCatching { it.stop() }; runCatching { it.flush() }; runCatching { it.close() } }
    }
}
