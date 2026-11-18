package app.mjolnir.android.throwing

/** Low-pass gravity estimate; [linear] returns the input with gravity subtracted. */
class GravityRemover(private val alpha: Float = 0.8f) {
    private val gravity = FloatArray(3)

    fun linear(x: Float, y: Float, z: Float): FloatArray {
        gravity[0] = alpha * gravity[0] + (1 - alpha) * x
        gravity[1] = alpha * gravity[1] + (1 - alpha) * y
        gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        return floatArrayOf(x - gravity[0], y - gravity[1], z - gravity[2])
    }
}
