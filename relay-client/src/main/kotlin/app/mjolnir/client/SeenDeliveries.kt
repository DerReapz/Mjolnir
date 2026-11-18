package app.mjolnir.client

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

interface SeenDeliveries {
    fun contains(id: String): Boolean
    fun add(id: String)
}

/** Remembers the newest [capacity] delivery ids on disk. */
class FileSeenDeliveries(file: File, private val capacity: Int = 500) : SeenDeliveries {
    private val store = JsonFileStore(file, ListSerializer(String.serializer())) { emptyList() }

    override fun contains(id: String): Boolean = id in store.read()

    override fun add(id: String) {
        store.update { ids -> if (id in ids) ids else (ids + id).takeLast(capacity) }
    }
}
