package app.mjolnir.client.voice

import java.util.concurrent.ConcurrentLinkedDeque

/** A microphone that yields a 1 kHz tone while [loud], else silence, paced at one 20 ms frame per read. */
class FakeMic(@Volatile var loud: Boolean = false) : AudioInput {
    @Volatile var started = false
    @Volatile var stopped = false
    private var index = 0

    override fun start() { started = true }

    override fun read(into: ShortArray): Int {
        if (stopped) return -1
        Thread.sleep(20)
        val frame = if (loud) Signals.tone(index) else Signals.silence()
        index++
        frame.copyInto(into)
        return frame.size
    }

    override fun stop() { stopped = true }
}

/** A speaker that keeps what it is given. */
class FakeSpeaker : AudioOutput {
    @Volatile var started = false
    @Volatile var stopped = false
    val frames = ConcurrentLinkedDeque<ShortArray>()

    override fun start() { started = true }
    override fun write(pcm: ShortArray) { frames.addLast(pcm.copyOf()); while (frames.size > 500) frames.pollFirst() }
    override fun stop() { stopped = true }

    /** RMS of the last [n] frames written (0 when none). */
    fun recentRms(n: Int = 10): Double {
        val last = frames.toList().takeLast(n)
        if (last.isEmpty()) return 0.0
        return last.map { Signals.rms(it) }.average()
    }
}
