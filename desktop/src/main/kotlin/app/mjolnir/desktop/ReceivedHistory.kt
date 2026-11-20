package app.mjolnir.desktop

import app.mjolnir.client.JsonFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

enum class Kind { LINK, FILE, FOLDER }

/** One received item. [target] is the URL, or the saved absolute path; [title] is the tab title/URL or the file/top-folder name. */
@Serializable
data class ReceivedEntry(
    val id: String,
    val fromDeviceId: String,
    val fromName: String,
    val atEpochMs: Long,
    val kind: Kind,
    val title: String,
    val target: String,
    /** The space the item arrived through, kept from the delivery; null for paired devices. */
    val space: String? = null,
)

/** What this PC has received, newest first, kept to the last [cap] entries and persisted through [store]. */
class ReceivedHistory(private val store: JsonFileStore<List<ReceivedEntry>>, private val cap: Int = 100) {
    private val state = MutableStateFlow(store.read())

    val entries: StateFlow<List<ReceivedEntry>> = state.asStateFlow()

    /** Prepends [entries] (kept in their given order) and trims to the cap. */
    fun add(entries: List<ReceivedEntry>) {
        if (entries.isEmpty()) return
        state.value = store.update { (entries + it).take(cap) }
    }

    fun clear() {
        store.write(emptyList())
        state.value = emptyList()
    }
}
