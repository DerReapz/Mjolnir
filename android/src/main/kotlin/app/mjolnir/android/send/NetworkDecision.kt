package app.mjolnir.android.send

import app.mjolnir.android.data.LargeFilePolicy

enum class NetDecision { GO, WAIT_FOR_WIFI }

/**
 * Whether a background throw of [totalBytes] may start now. It waits for Wi-Fi only on a [metered] network, with the
 * WAIT_FOR_WIFI policy, without a one-off `Send now` ([allowedOnce]), and when it is over the threshold.
 */
fun networkDecision(totalBytes: Long, metered: Boolean, policy: LargeFilePolicy, thresholdMb: Int, allowedOnce: Boolean): NetDecision =
    if (metered && policy == LargeFilePolicy.WAIT_FOR_WIFI && !allowedOnce && totalBytes > thresholdMb * 1_048_576L) {
        NetDecision.WAIT_FOR_WIFI
    } else {
        NetDecision.GO
    }

/** The status line of an *On the way* row. */
fun outgoingLabel(running: Boolean, progressPct: Int?, currentName: String?, waitingForWifi: Boolean): String = when {
    running && progressPct == null -> "Throwing…"
    running && currentName == null -> "Throwing… $progressPct%"
    running -> "Throwing $currentName… $progressPct%"
    waitingForWifi -> "Waiting for Wi-Fi"
    else -> "Waiting for a connection"
}

/** Whole percent of [done] out of [total], 0..100. */
fun progressPercent(done: Long, total: Long): Int =
    if (total <= 0) 0 else (done.coerceIn(0, total) * 100 / total).toInt()

/** The display name of the file being uploaded once [done] bytes of all [files] (relative path to size) are sent. */
fun currentFileName(files: List<Pair<String, Long>>, done: Long): String? {
    var end = 0L
    for ((path, size) in files) {
        end += size
        if (done < end) return path.substringAfterLast('/')
    }
    return files.lastOrNull()?.first?.substringAfterLast('/')
}
