package app.mjolnir.desktop

import java.io.File

private val LICENCE_FILES = listOf("OFL-Inter.txt", "OFL-SpaceGrotesk.txt")

/** Copies the bundled font licences (SIL OFL) out of the jar into [into] and returns that folder. */
fun extractLicences(into: File): File {
    into.mkdirs()
    for (name in LICENCE_FILES) {
        val bytes = AppController::class.java.getResourceAsStream("/fonts/$name")?.use { it.readBytes() } ?: continue
        File(into, name).writeBytes(bytes)
    }
    return into
}
