package app.mjolnir.desktop

import java.io.File
import java.io.FileOutputStream

/** Tags [file] as downloaded from the internet (zone 3) via its Zone.Identifier stream. Failures are ignored. */
fun markOfTheWeb(file: File) {
    try {
        FileOutputStream(file.path + ":Zone.Identifier").use { it.write("[ZoneTransfer]\r\nZoneId=3\r\n".toByteArray()) }
    } catch (e: Exception) {
        // non-NTFS drive or non-Windows
    }
}
