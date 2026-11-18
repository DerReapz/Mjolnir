package app.mjolnir.android.throwing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Feeds acceleration samples to a [ThrowDetector] and calls [onFlick] (on the main thread) when it fires.
 *
 * With `TYPE_LINEAR_ACCELERATION` the sensor is registered only while held ([start] .. [stop]). Without it,
 * `TYPE_ACCELEROMETER` is piped through [GravityRemover]; that estimate needs time to converge, so [prepare]
 * (call while the screen is visible) registers it early. The detector itself only counts while held.
 * [release] unregisters everything (call when the screen leaves).
 */
class FlickSensor(context: Context, private val detector: ThrowDetector, private val onFlick: () -> Unit) {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linear: Sensor? = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelerometer: Sensor? = if (linear == null) manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
    private val gravity = GravityRemover()
    private var registered = false
    private var held = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values.let { Triple(it[0], it[1], it[2]) }
            val a = if (linear != null) floatArrayOf(x, y, z) else gravity.linear(x, y, z) // always fed, so gravity converges
            if (held && detector.onSample(event.timestamp, a[0], a[1], a[2])) onFlick()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Starts the accelerometer fallback listening before any press; a no-op with a linear-acceleration sensor. */
    fun prepare() {
        if (accelerometer != null) register()
    }

    /** The user pressed: starts counting (and listening, if not already). */
    fun start() {
        held = true
        detector.onHoldStart(SystemClock.elapsedRealtimeNanos())
        register()
    }

    /** The user let go, or the throw fired: stops counting. The accelerometer fallback keeps listening for [prepare]. */
    fun stop() {
        held = false
        detector.onHoldEnd()
        if (accelerometer == null) unregister()
    }

    /** The screen is leaving: stops listening altogether. */
    fun release() {
        stop()
        unregister()
    }

    private fun register() {
        if (registered) return
        val sensor = linear ?: accelerometer ?: return
        registered = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregister() {
        if (!registered) return
        manager.unregisterListener(listener)
        registered = false
    }
}
