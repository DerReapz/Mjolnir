package app.mjolnir.desktop

/** Added to the sign-in launch command, so Mjolnir starts in the tray there but opens its window from the Start menu. */
const val BACKGROUND_FLAG = "--background"

data class LaunchArgs(val background: Boolean, val paths: List<String>)

/** Splits the command line into the [BACKGROUND_FLAG] and the paths to send (made absolute, blanks dropped). */
fun parseLaunchArgs(args: Array<String>): LaunchArgs = LaunchArgs(
    background = BACKGROUND_FLAG in args,
    paths = args.filter { it.isNotBlank() && it != BACKGROUND_FLAG }.map { java.io.File(it).absolutePath },
)

/** A cold start shows the main window unless it was a background (sign-in) start; no relay yet always shows it (Settings). */
fun showWindowAtStart(background: Boolean, needsRelay: Boolean): Boolean = needsRelay || !background
