package app.mjolnir.client.voice

/** The microphone: 48 kHz mono 16-bit PCM. The apps implement it over their audio APIs. */
interface AudioInput {
    fun start()

    /** Fills [into] with the next 20 ms frame (960 samples), blocking until it is captured; returns the samples read, or -1 once stopped. */
    fun read(into: ShortArray): Int

    /** Stops capture; a blocked [read] returns. May be called more than once. */
    fun stop()
}

/** The speaker: 48 kHz mono 16-bit PCM. */
interface AudioOutput {
    fun start()

    /** Plays one 20 ms frame. */
    fun write(pcm: ShortArray)

    /** May be called more than once. */
    fun stop()
}

/** Opens the audio devices of one voice session; each session gets a fresh pair. */
interface VoiceAudioFactory {
    fun input(): AudioInput
    fun output(): AudioOutput
}
