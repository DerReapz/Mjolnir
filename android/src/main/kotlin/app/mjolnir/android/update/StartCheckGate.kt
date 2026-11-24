package app.mjolnir.android.update

import java.io.File
import java.io.IOException

/**
 * Whether an app start should check GitHub for an update right away: not when a start check ran under
 * [START_CHECK_GAP_MS] ago (Android starts the process often; the 6-hour loop and the relay's pushes are unaffected).
 * The time of each start check is kept in [file], so it holds across processes; a clock that went back checks.
 */
class StartCheckGate(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    fun checkNow(): Boolean {
        val now = clock()
        val last = try {
            file.readText().trim().toLongOrNull()
        } catch (_: IOException) {
            null
        }
        if (last != null && now - last in 0 until START_CHECK_GAP_MS) return false
        try {
            file.parentFile?.mkdirs()
            file.writeText(now.toString())
        } catch (_: IOException) {
            // Not recorded: the next start checks again, as before.
        }
        return true
    }

    companion object {
        const val START_CHECK_GAP_MS = 60 * 60 * 1000L
    }
}
