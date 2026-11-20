package app.mjolnir.client

import java.util.Locale

/** User-facing copy shared by the desktop and Android apps. */
object Messages {
    const val RESET = "This device was reset on the relay. Pair again."
    const val NO_LINKS = "No links found in what you shared"

    private fun tabs(n: Int) = if (n == 1) "1 tab" else "$n tabs"
    private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

    fun items(tabs: Int, files: Int): String = when {
        tabs > 0 && files > 0 -> "${tabs(tabs)} and ${files(files)}"
        files > 0 -> files(files)
        else -> tabs(tabs)
    }

    private fun tagged(text: String, space: String?) = if (space == null) text else "$text · $space"

    fun caught(tabs: Int, files: Int, from: String, space: String? = null) = tagged("Caught ${items(tabs, files)} from $from", space)
    fun thrown(tabs: Int, files: Int, to: String, space: String? = null) = tagged("Thrown ${items(tabs, files)} to $to", space)
    fun notThrown(reason: String) = "Not thrown: $reason. Your tabs are still here."
    fun sent(tabs: Int, files: Int, to: String) = "Sent ${items(tabs, files)} to $to"
    fun tooBig(name: String) = "$name is too big (max 100 MB)"
    fun unreadable(name: String) = "Couldn't read $name"

    fun size(bytes: Long): String {
        val kb = 1024L; val mb = kb * 1024; val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / gb)
            bytes >= mb -> "${Math.round(bytes.toDouble() / mb)} MB"
            bytes >= kb -> "${Math.round(bytes.toDouble() / kb)} KB"
            else -> "$bytes B"
        }
    }

    fun queued(files: Int, to: String) = "Throwing ${files(files)} to $to in the background"
    fun waitingToThrow(files: Int, bytes: Long, to: String) = "Waiting for Wi-Fi to throw ${files(files)} (${size(bytes)}) to $to"
    fun waitingToReceive(files: Int, bytes: Long, from: String) = "Waiting for Wi-Fi: ${files(files)} (${size(bytes)}) from $from"
    fun filesBack(reason: String) = "Not thrown: $reason. Your files are back in the tray."
    fun tooManyFiles(folder: String) = "$folder has too many files (max 1000)"
    fun emptyFolder(folder: String) = "$folder has no files"
    fun leftOut(n: Int) = if (n == 1) "1 file over 100 MB was left out" else "$n files over 100 MB were left out"
    fun notSent(reason: String) = "Not sent: $reason."

    fun failureReason(kind: RelayErrorKind, peerName: String): String = when (kind) {
        RelayErrorKind.NETWORK -> "no connection"
        RelayErrorKind.NOT_PAIRED -> "$peerName is no longer paired"
        RelayErrorKind.RATE_LIMITED -> "too many throws, wait a minute"
        RelayErrorKind.UNAUTHORIZED -> "this device was reset on the relay"
        RelayErrorKind.TOO_LARGE -> "a file is too big for the relay"
        RelayErrorKind.STORAGE_FULL -> "the relay is out of space"
        else -> "the relay had a problem"
    }
}
