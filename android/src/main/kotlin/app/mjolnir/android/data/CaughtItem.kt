package app.mjolnir.android.data

import app.mjolnir.protocol.Tab
import kotlinx.serialization.Serializable

/** A received file published to Downloads; [uri] is its content:// URI. */
@Serializable
data class SavedFile(val name: String, val uri: String, val sizeBytes: Long, val mimeType: String)

/** A received folder (its unique top-level name under Download/Mjolnir). */
@Serializable
data class SavedFolder(val name: String, val fileCount: Int, val totalBytes: Long)

/** One row of the Caught screen: exactly one of [tab], [file] or [folder] is set. */
@Serializable
data class CaughtItem(
    val tab: Tab? = null,
    val file: SavedFile? = null,
    val folder: SavedFolder? = null,
    val fromName: String,
    val caughtAtEpochMs: Long,
)
