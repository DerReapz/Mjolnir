package app.mjolnir.client.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

sealed interface VoiceState {
    /** Opening the first link. */
    data object Connecting : VoiceState
    /** In the room: the relay sent its roster. */
    data object Live : VoiceState
    /** The link dropped; trying again (for up to 30 s). */
    data object Reconnecting : VoiceState
    /**
     * Over. [reason] is null for [VoiceSession.leave]; `connection_lost` when reconnecting gave up; the relay's reason
     * (`room_full`, `removed`, `room_deleted`, `replaced`, `not_found`) when it closed the link; [VoiceSession.AUDIO_ERROR]
     * when the microphone or speaker failed.
     */
    data class Ended(val reason: String?) : VoiceState
}

/** Paces the playout: returns once per 20 ms frame. */
fun interface VoiceTicker {
    suspend fun awaitTick()
}

/** A fixed-rate 20 ms tick on [nowMs]; when it falls far behind (a stalled device) it starts afresh rather than bursting. */
class RealtimeTicker(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) : VoiceTicker {
    private var next = -1L

    override suspend fun awaitTick() {
        val now = nowMs()
        if (next < 0 || now - next > MAX_BEHIND_MS) next = now
        next += OpusCodec.FRAME_MS
        val wait = next - nowMs()
        if (wait > 0) delay(wait)
    }

    private companion object {
        const val MAX_BEHIND_MS = 100L
    }
}

/**
 * One device in one voice room: captures [input], sends it over a link from [links], and plays what the others say on
 * [output]. Three loops run in [scope]:
 * - capture: read a frame, detect speech, gate (mute, push to talk or open mic), encode, send;
 * - receive: each frame goes into its speaker's [JitterBuffer] (on the link's reader);
 * - playout, every 20 ms: pop each speaker, decode, mix, write.
 *
 * An unexpected close reconnects with backoff for up to [reconnectLimitMs], then ends with `connection_lost`; a close
 * with a relay reason ends at once with it. Every sent frame carries a sequence number that advances every 20 ms whether
 * or not the frame is sent, so the receiver's buffer sees silences as gaps in time.
 *
 * [selfId], when given, is put in [speaking] while this device's own speech is being sent.
 */
class VoiceSession(
    private val links: VoiceLinkFactory,
    private val input: AudioInput,
    private val output: AudioOutput,
    private val scope: CoroutineScope,
    private val selfId: String? = null,
    private val reconnectLimitMs: Long = RECONNECT_LIMIT_MS,
    private val ticker: VoiceTicker = RealtimeTicker(),
    private val audioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val backoffMs: (attempt: Int) -> Long = { min(MAX_BACKOFF_MS, 250L shl it.coerceIn(0, 4)) },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Connecting)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _speaking = MutableStateFlow<Set<String>>(emptySet())
    /** Device ids speaking right now (this device's own [selfId] too, while its speech is sent). */
    val speaking: StateFlow<Set<String>> = _speaking.asStateFlow()

    @Volatile private var muted = false
    @Volatile private var talking = false
    /** Open mic sends only what sounds like speech; otherwise frames are sent while [setTalking] holds the gate. */
    @Volatile var openMic: Boolean = false

    fun setMuted(muted: Boolean) { this.muted = muted }

    /** The push-to-talk gate; ignored on [openMic]. */
    fun setTalking(talking: Boolean) { this.talking = talking }

    private class Speaker {
        val buffer = JitterBuffer()
        val codec = OpusCodec()
        val detector = SpeakingDetector()
        var speaking = false
    }

    /** Guards [speakers] and every [JitterBuffer] in it (pushed by the link's reader, popped by playout). */
    private val lock = Any()
    private val speakers = LinkedHashMap<String, Speaker>()
    @Volatile private var roster: List<String> = emptyList()
    @Volatile private var selfSpeaking = false
    @Volatile private var current: VoiceLink? = null
    private val ended = AtomicBoolean(false)
    private val jobs = mutableListOf<Job>()

    init {
        synchronized(jobs) {
            // Undispatched, so the first link is open (and closeable by leave) when the constructor returns.
            jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { connect() }
            jobs += scope.launch(audioDispatcher) { capture() }
            jobs += scope.launch(audioDispatcher) { playout() }
        }
    }

    /** Leaves the room for good: the relay drops this device at once. */
    fun leave() = finish(null)

    private fun finish(reason: String?) {
        if (!ended.compareAndSet(false, true)) return
        _state.value = VoiceState.Ended(reason)
        current?.close()
        current = null
        synchronized(jobs) { jobs.forEach { it.cancel() } }
        runCatching { input.stop() } // unblocks a pending read
        synchronized(lock) { speakers.clear() }
        _speaking.value = emptySet()
    }

    private fun setState(s: VoiceState) = _state.update { if (it is VoiceState.Ended) it else s }

    private suspend fun connect() {
        var lostSince = nowMs()
        var attempt = 0
        var everLive = false
        while (!ended.get()) {
            val live = AtomicBoolean(false)
            val link = links.open(
                onRoster = { ids ->
                    roster = ids
                    synchronized(lock) { speakers.keys.retainAll(ids.toSet()) }
                    live.set(true)
                    setState(VoiceState.Live)
                },
                onFrame = ::onFrame,
            )
            current = link
            if (ended.get()) { link.close(); return }
            val reason = link.closeReason.await()
            current = null
            if (ended.get()) return
            if (reason != null) return finish(reason)
            roster = emptyList()
            synchronized(lock) { speakers.clear() }
            if (live.get()) { everLive = true; lostSince = nowMs(); attempt = 0 }
            val elapsed = nowMs() - lostSince
            if (elapsed >= reconnectLimitMs) return finish(CONNECTION_LOST)
            setState(if (everLive) VoiceState.Reconnecting else VoiceState.Connecting)
            delay(min(backoffMs(attempt++), reconnectLimitMs - elapsed))
        }
    }

    private fun onFrame(speakerIndex: Int, seq: Int, opus: ByteArray) {
        val id = roster.getOrNull(speakerIndex) ?: return
        if (id == selfId) return
        synchronized(lock) {
            if (ended.get()) return
            speakers.getOrPut(id) { Speaker() }.buffer.push(seq, opus)
        }
    }

    private fun capture() {
        val frame = ShortArray(OpusCodec.FRAME_SAMPLES)
        val codec = OpusCodec()
        val detector = SpeakingDetector()
        // On open mic, the frames just before speech was recognised, so its start is not cut off.
        val preRoll = ArrayDeque<Pair<Int, ByteArray>>()
        var seq = 0
        var sending = false
        try {
            if (ended.get()) return
            input.start()
            if (ended.get()) return input.stop()
            while (!ended.get()) {
                val n = input.read(frame)
                if (n < 0 || ended.get()) break
                if (n < frame.size) frame.fill(0, n.coerceAtLeast(0))
                val s = seq
                seq = (seq + 1) and 0xFFFF
                if (muted) {
                    detector.reset(); preRoll.clear(); sending = false; selfSpeaking = false
                    continue
                }
                val voiced = detector.update(frame)
                val open = openMic
                val gate = if (open) voiced else talking
                if (gate) {
                    val packet = codec.encode(frame)
                    val link = current
                    if (!sending) preRoll.forEach { (ps, p) -> link?.send(ps, p) }
                    preRoll.clear()
                    link?.send(s, packet)
                } else if (open) {
                    preRoll.addLast(s to codec.encode(frame))
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                } else {
                    preRoll.clear()
                }
                sending = gate
                selfSpeaking = gate && voiced
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finish(AUDIO_ERROR)
        } finally {
            selfSpeaking = false
        }
    }

    private suspend fun playout() {
        val silence = ShortArray(OpusCodec.FRAME_SAMPLES)
        try {
            if (ended.get()) return
            output.start()
            while (scope.isActive && !ended.get()) {
                ticker.awaitTick()
                val frames = ArrayList<ShortArray>()
                val now = HashSet<String>()
                val due = synchronized(lock) {
                    speakers.map { (id, sp) ->
                        val wasActive = sp.buffer.active
                        val packet = sp.buffer.pop()
                        // A gap mid-speech is concealed (or rebuilt from the next packet's FEC); a quiet speaker plays nothing.
                        val play = packet != null || wasActive
                        Triple(id, sp, if (play) (packet to if (packet == null) sp.buffer.peek() else null) else null)
                    }
                }
                for ((id, sp, item) in due) {
                    val pcm = item?.let { (packet, next) -> sp.codec.decode(packet, next) }
                    if (pcm != null) frames += pcm
                    sp.speaking = sp.detector.update(pcm ?: silence)
                    if (sp.speaking) now += id
                }
                output.write(Mixer.mix(frames))
                if (selfSpeaking && selfId != null) now += selfId
                if (!ended.get() && _speaking.value != now) _speaking.value = now
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finish(AUDIO_ERROR)
        } finally {
            runCatching { output.stop() }
        }
    }

    companion object {
        const val RECONNECT_LIMIT_MS = 30_000L
        private const val MAX_BACKOFF_MS = 2_000L
        /** 100 ms: what the speaking detector needs before it says speech. */
        private const val PRE_ROLL_FRAMES = 5
        const val CONNECTION_LOST = "connection_lost"
        const val AUDIO_ERROR = "audio_error"
    }
}
