package app.mjolnir.client

import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.Limits
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Files queued for one background send to [to]. */
@Serializable
data class OutgoingFiles(
    val id: String,
    val to: String,
    val toName: String,
    val files: List<LocalUpload>,
    val allowMetered: Boolean = false,
    val createdAtEpochMs: Long,
    /** The space whose card the files were thrown from; null for a paired device. */
    val spaceId: String? = null,
    /** [spaceId]'s name when queued, for notifications; null for a paired device. */
    val spaceName: String? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

sealed interface OutboxResult {
    data class Done(val item: OutgoingFiles) : OutboxResult
    /** NETWORK, SERVER, RATE_LIMITED: state kept. Also returned for an id that is no longer queued. */
    data object Retry : OutboxResult
    /**
     * Anything else: item removed (cleanup not called, so the caller can give the files back). [message] is a
     * specific reason such as "Couldn't read a.jpg" that callers show instead of the kind's generic one.
     */
    data class Failed(val item: OutgoingFiles, val kind: RelayErrorKind, val message: String? = null) : OutboxResult
}

/** Persistent queue of background file sends that survives restarts and resumes interrupted uploads. */
class Outbox(
    private val store: JsonFileStore<List<OutgoingFiles>>,
    private val api: () -> RelayApi,
    private val open: (LocalUpload, offset: Long) -> InputStream,
    private val cleanup: (LocalUpload) -> Unit = {},
    private val chunkBytes: Long = Limits.UPLOAD_CHUNK_BYTES,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun enqueue(item: OutgoingFiles) {
        require(item.files.size in 1..Limits.MAX_FILES_PER_SEND) { "files.size out of range: ${item.files.size}" }
        store.update { list -> list.filter { it.id != item.id } + item }
    }

    fun pending(): List<OutgoingFiles> = store.read()

    fun get(id: String): OutgoingFiles? = store.read().find { it.id == id }

    fun allowMetered(id: String) {
        store.update { list -> list.map { if (it.id == id) it.copy(allowMetered = true) else it } }
    }

    suspend fun process(id: String, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): OutboxResult =
        locks.computeIfAbsent(id) { Mutex() }.withLock { processLocked(id, onProgress) }

    private suspend fun processLocked(id: String, onProgress: (Long, Long) -> Unit): OutboxResult {
        var item = get(id) ?: return OutboxResult.Retry
        val api = api()

        // The relay drops unsent files after an hour: re-upload any finished file it no longer has.
        for ((index, file) in item.files.withIndex()) {
            val ref = file.ref ?: continue
            try {
                api.uploadStatus(ref.id)
            } catch (e: RelayException) {
                if (e.kind != RelayErrorKind.NOT_FOUND) return failure(item, e)
                item = persistFile(id, index, file.copy(uploadId = null, ref = null)) ?: item
            }
        }

        val total = item.totalBytes
        var finished = item.files.filter { it.ref != null }.sumOf { it.sizeBytes }
        var reported = -1L
        fun report(done: Long) {
            if (done > reported) { reported = done; onProgress(done, total) }
        }
        report(finished)

        val refs = mutableListOf<FileRef>()
        for ((index, file) in item.files.withIndex()) {
            val ref = try {
                uploadResumable(
                    api, file, open = { offset -> open(file, offset) },
                    onState = { state -> item = persistFile(id, index, state) ?: item },
                    onProgress = { bytes -> report(finished + bytes) },
                    chunkBytes = chunkBytes,
                )
            } catch (e: RelayException) {
                return failure(item, e)
            }
            if (file.ref == null) finished += file.sizeBytes
            refs += ref
        }
        report(total)

        val error = sendInBatches(api, item.to, emptyList(), refs, item.spaceId).error ?: return done(item)
        // A lost response: the relay already holds this delivery (or the recipient acked it, leaving tombstones).
        if (error.kind == RelayErrorKind.ALREADY_SENT) return done(item)
        return failure(item, error)
    }

    private fun failure(item: OutgoingFiles, e: RelayException): OutboxResult = when (e.kind) {
        RelayErrorKind.NETWORK, RelayErrorKind.SERVER, RelayErrorKind.RATE_LIMITED -> OutboxResult.Retry
        else -> {
            remove(item.id)
            OutboxResult.Failed(item, e.kind, e.userMessage)
        }
    }

    private fun done(item: OutgoingFiles): OutboxResult {
        remove(item.id)
        for (f in item.files) runCatching { cleanup(f) }
        return OutboxResult.Done(item)
    }

    private fun remove(id: String) {
        store.update { list -> list.filter { it.id != id } }
    }

    private fun persistFile(id: String, index: Int, state: LocalUpload): OutgoingFiles? =
        persist(id) { it.copy(files = it.files.toMutableList().also { files -> files[index] = state }) }

    /** Applies [change] to the stored item (keeping concurrent edits such as allowMetered) and returns the result. */
    private fun persist(id: String, change: (OutgoingFiles) -> OutgoingFiles): OutgoingFiles? {
        var updated: OutgoingFiles? = null
        store.update { list -> list.map { if (it.id == id) change(it).also { u -> updated = u } else it } }
        return updated
    }
}
