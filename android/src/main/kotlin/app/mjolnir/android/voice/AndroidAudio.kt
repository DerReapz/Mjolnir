package app.mjolnir.android.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import app.mjolnir.client.RelayApi
import app.mjolnir.client.voice.AudioInput
import app.mjolnir.client.voice.AudioOutput
import app.mjolnir.client.voice.VoiceAudioFactory
import app.mjolnir.client.voice.VoiceController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val SAMPLE_RATE = 48_000
private const val FRAME_SAMPLES = 960
private const val FRAME_BYTES = FRAME_SAMPLES * 2

/** The phone's voice rooms: open mic by default (ruling: phone open mic, PC push to talk). */
fun phoneVoiceController(
    api: RelayApi, scope: CoroutineScope, audio: VoiceAudioFactory, selfId: () -> String?, audioDispatcher: CoroutineDispatcher,
): VoiceController = VoiceController(api, scope, audio, selfId, openMicByDefault = true, audioDispatcher = audioDispatcher)

/**
 * The threads capture and playout block on, at the system's urgent-audio priority so a busy phone doesn't starve them:
 * two per session (one capturing, one playing), with room for a session handover.
 */
fun newVoiceAudioDispatcher(): CoroutineDispatcher {
    val n = AtomicInteger()
    return Executors.newFixedThreadPool(4, ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            r.run()
        }, "mjolnir-voice-audio-${n.incrementAndGet()}").apply { isDaemon = true }
    }).asCoroutineDispatcher()
}

/**
 * `AudioManager.mode` is `MODE_IN_COMMUNICATION` while any voice device is open, and goes back to what it was when the
 * last one closes. [apply] (true: set, false: restore) runs under the lock, so a claim and a release never interleave.
 */
internal class CommunicationMode(private val apply: (on: Boolean) -> Unit) {
    private val lock = Any()
    private var open = 0

    /** One open device; release it exactly when the device closes (a second release does nothing). */
    inner class Claim internal constructor() {
        private val released = AtomicBoolean(false)
        fun release() {
            if (!released.compareAndSet(false, true)) return
            synchronized(lock) { if (--open == 0) apply(false) }
        }
    }

    fun claim(): Claim = synchronized(lock) {
        if (open++ == 0) apply(true)
        Claim()
    }
}

/**
 * The microphone and speaker of voice sessions: `AudioRecord` on `VOICE_COMMUNICATION` (the platform's echo canceller and
 * noise suppressor attached when the phone has them) and `AudioTrack` for voice communication, both 48 kHz mono 16-bit.
 * A device that fails to open throws from `start()`, which ends the session with the audio error.
 */
class AndroidAudioFactory(context: Context) : VoiceAudioFactory {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mode = CommunicationMode(::setCommunication)
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false

    override fun input(): AudioInput = AndroidAudioInput(appContext, mode)
    override fun output(): AudioOutput = AndroidAudioOutput(mode)

    /** Under [CommunicationMode]'s lock. Without a headset the room plays on the loudspeaker, not the earpiece. */
    @Suppress("DEPRECATION") // isSpeakerphoneOn: the way to route below Android 12
    private fun setCommunication(on: Boolean) {
        val am = audioManager ?: return
        try {
            if (on) {
                previousMode = am.mode
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val devices = am.availableCommunicationDevices
                    if (devices.none { it.type in HEADSET_TYPES }) {
                        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let(am::setCommunicationDevice)
                    }
                } else {
                    previousSpeaker = am.isSpeakerphoneOn
                    if (am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type in HEADSET_TYPES }) am.isSpeakerphoneOn = true
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = previousSpeaker
                am.mode = previousMode
            }
        } catch (_: RuntimeException) {
            // Routing is best effort; the session still runs on the default route.
        }
    }

    private companion object {
        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET, 26 /* TYPE_BLE_HEADSET (API 31) */,
        )
    }
}

private class AndroidAudioInput(private val context: Context, private val mode: CommunicationMode) : AudioInput {
    private val lock = Any()
    @Volatile private var record: AudioRecord? = null
    @Volatile private var stopped = false
    private var claim: CommunicationMode.Claim? = null
    private val effects = mutableListOf<AudioEffect>()

    override fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("no microphone permission")
        }
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "no 48 kHz capture" }
        synchronized(lock) {
            if (stopped) return
            claim = mode.claim()
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, FRAME_BYTES * 4),
            )
            record = r
            check(r.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.let { it.enabled = true; effects += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.let { it.enabled = true; effects += it }
            r.startRecording()
            check(r.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microphone busy" }
        }
    }

    override fun read(into: ShortArray): Int {
        var got = 0
        while (got < FRAME_SAMPLES) {
            if (stopped) return -1
            val n = synchronized(lock) {
                val r = record ?: return -1
                if (stopped) return -1
                r.read(into, got, FRAME_SAMPLES - got, AudioRecord.READ_BLOCKING)
            }
            if (n <= 0) return -1 // stopped, or the device failed
            got += n
        }
        return got
    }

    /** `AudioRecord.stop()` returns a blocked read at once; the record is freed once that read has let go of it. */
    override fun stop() {
        stopped = true
        record?.let { runCatching { it.stop() } }
        synchronized(lock) {
            effects.forEach { runCatching { it.release() } }
            effects.clear()
            record?.let { runCatching { it.release() } }
            record = null
            claim?.release()
            claim = null
        }
    }
}

private class AndroidAudioOutput(private val mode: CommunicationMode) : AudioOutput {
    private val lock = Any()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false
    private var claim: CommunicationMode.Claim? = null

    override fun start() {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "no 48 kHz playback" }
        synchronized(lock) {
            if (stopped) return
            claim = mode.claim()
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 6)) // about 120 ms: room for jitter, little added delay
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            check(t.state == AudioTrack.STATE_INITIALIZED) { "speaker unavailable" }
            t.play()
        }
    }

    override fun write(pcm: ShortArray) {
        synchronized(lock) {
            val t = track ?: return
            if (stopped) return
            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    /** `pause()` returns a blocked write at once; the track is freed once that write has let go of it. */
    override fun stop() {
        stopped = true
        track?.let { runCatching { it.pause() }; runCatching { it.flush() } }
        synchronized(lock) {
            track?.let { runCatching { it.stop() }; runCatching { it.release() } }
            track = null
            claim?.release()
            claim = null
        }
    }
}
