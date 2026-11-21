package app.mjolnir.client

import app.mjolnir.protocol.Attachment
import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.ChatMessage
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.Limits
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

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

// ---- Chat sends: files posted as a chat message once uploaded ----

/** One picked file of a chat send and how it is shown, persisted with its upload state. */
@Serializable
data class OutgoingAttachment(
    val upload: LocalUpload,
    val kind: AttachmentKind,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    /** The thumbnail JPEG (a file of the [ChatOutbox]) uploaded with it, if any. */
    val thumb: LocalUpload? = null,
    /** Path inside a sent folder, top folder first; null for a single file. */
    val relativePath: String? = null,
) {
    val name: String get() = upload.relativePath.substringAfterLast('/')
    internal val totalBytes: Long get() = upload.sizeBytes + (thumb?.sizeBytes ?: 0)
    internal val uploadedBytes: Long get() = (if (upload.ref != null) upload.sizeBytes else 0) + (if (thumb?.ref != null) thumb.sizeBytes else 0)

    internal fun toAttachment() = Attachment(
        fileId = upload.ref!!.id, kind = kind, name = name, sizeBytes = upload.sizeBytes, mime = upload.mimeType,
        width = width, height = height, durationMs = durationMs, thumbFileId = thumb?.ref?.id, relativePath = relativePath,
    )
}

/** A chat send with attachments, queued until all its files are uploaded and posted. */
@Serializable
data class OutgoingMessage(
    val id: String,
    val chatId: String,
    val text: String? = null,
    val mentions: List<String> = emptyList(),
    val attachments: List<OutgoingAttachment>,
    val createdAtEpochMs: Long,
    /**
     * How many [attachments] are posted. A send of more than [Limits.MAX_ATTACHMENTS_PER_MESSAGE] goes out as consecutive
     * messages of at most that many; [text] and [mentions] go with the first.
     */
    val posted: Int = 0,
)

sealed interface ChatOutboxResult {
    /** Everything is posted. [unconfirmed]: a post's response was lost, so the chat needs a sync to show it. */
    data class Done(val unconfirmed: Boolean) : ChatOutboxResult
    /** NETWORK, SERVER, RATE_LIMITED: state kept for the next try. Also returned for an id no longer queued. */
    data object Retry : ChatOutboxResult
    /** Anything else: the send is removed. */
    data class Failed(val error: RelayException) : ChatOutboxResult
}

/**
 * Persistent queue of chat sends with attachments in [dir], surviving restarts. Each message's files are uploaded (resuming
 * interrupted uploads) right before it is posted, and it is posted only once all of them are on the relay. The relay drops
 * unused uploads after an hour: a post refused with invalid_files uploads the expired files once more and posts again.
 */
class ChatOutbox(
    private val dir: File,
    private val chunkBytes: Long = Limits.UPLOAD_CHUNK_BYTES,
    private val open: (LocalUpload, offset: Long) -> InputStream = { u, offset -> FileInputStream(u.path).also { it.channel.position(offset) } },
) {
    private val store = JsonFileStore(File(dir, "chat-outbox.json"), ListSerializer(OutgoingMessage.serializer())) { emptyList() }
    private val thumbs = File(dir, "thumbs")
    private val lock = Mutex()

    /** Queued sends, oldest first: the order they must be posted in. */
    fun pending(): List<OutgoingMessage> = store.read()

    fun get(id: String): OutgoingMessage? = store.read().find { it.id == id }

    /** Where a send keeps the thumbnail of its attachment number [index] until it is posted. */
    fun thumbFile(sendId: String, index: Int): File = File(thumbs, "$sendId-$index.jpg")

    fun enqueue(item: OutgoingMessage) {
        require(item.attachments.isNotEmpty()) { "a chat send without attachments is posted directly" }
        store.update { list -> list.filter { it.id != item.id } + item }
    }

    /** Drops [id] and its thumbnails. */
    fun remove(id: String) {
        var removed: OutgoingMessage? = null
        store.update { list -> list.filter { (it.id != id).also { keep -> if (!keep) removed = it } } }
        removed?.attachments?.forEach { a -> a.thumb?.let { File(it.path).delete() } }
    }

    /** Drops every send and thumbnail. */
    fun clear() {
        store.write(emptyList())
        thumbs.deleteRecursively()
    }

    /**
     * Uploads and posts the rest of [id]. [onProgress] gets the bytes of the whole send that are on the relay, [onPosted]
     * each message as it is posted.
     */
    suspend fun process(
        id: String,
        api: RelayApi,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        onPosted: (ChatMessage) -> Unit = {},
    ): ChatOutboxResult = lock.withLock {
        var item = get(id) ?: return ChatOutboxResult.Retry
        val total = item.attachments.sumOf { it.totalBytes }
        var unconfirmed = false
        while (item.posted < item.attachments.size) {
            val range = item.posted until minOf(item.posted + Limits.MAX_ATTACHMENTS_PER_MESSAGE, item.attachments.size)
            var uploadedAgain = false
            while (true) {
                for (i in range) {
                    item = try {
                        upload(id, item, i, api) { done -> onProgress(done, total) }
                    } catch (e: RelayException) {
                        return failure(item, e)
                    }
                }
                if (get(id) == null) return ChatOutboxResult.Retry // cleared meanwhile: never post
                val first = item.posted == 0
                try {
                    val message = api.postMessage(
                        item.chatId, if (first) item.text else null, if (first) item.mentions else emptyList(),
                        range.map { item.attachments[it].toAttachment() },
                    )
                    onPosted(message)
                } catch (e: RelayException) {
                    if (e.kind != RelayErrorKind.INVALID || e.message != "invalid_files" || uploadedAgain) return failure(item, e)
                    uploadedAgain = true
                    var expired = false
                    try {
                        for (i in range) {
                            val a = item.attachments[i]
                            val upload = if (gone(api, a.upload)) a.upload.restarted() else a.upload
                            val thumb = a.thumb?.let { if (gone(api, it)) it.restarted() else it }
                            if (upload == a.upload && thumb == a.thumb) continue
                            expired = true
                            item = persist(id) { m -> m.withAttachment(i) { it.copy(upload = upload, thumb = thumb) } } ?: return ChatOutboxResult.Retry
                        }
                    } catch (e2: RelayException) {
                        return failure(item, e2)
                    }
                    if (expired) continue
                    // Every file is still there, so they are attached: an earlier post went through and its response was lost.
                    unconfirmed = true
                }
                item = persist(id) { it.copy(posted = range.last + 1) } ?: return ChatOutboxResult.Retry
                break
            }
        }
        onProgress(total, total)
        remove(id)
        ChatOutboxResult.Done(unconfirmed)
    }

    /** Uploads attachment [index] of [item] and its thumbnail, persisting each step; returns the updated item. */
    private suspend fun upload(id: String, item: OutgoingMessage, index: Int, api: RelayApi, onProgress: (Long) -> Unit): OutgoingMessage {
        var current = item
        val before = current.attachments.withIndex().sumOf { (i, a) -> if (i < current.posted) a.totalBytes else a.uploadedBytes }
        var base = before
        for (isThumb in listOf(false, true)) {
            val att = current.attachments[index]
            val file = (if (isThumb) att.thumb else att.upload) ?: continue
            if (file.ref != null) continue
            uploadResumable(
                api, file, open = { offset -> open(file, offset) },
                onState = { state ->
                    current = persist(id) { m -> m.withAttachment(index) { a -> if (isThumb) a.copy(thumb = state) else a.copy(upload = state) } }
                        ?: current.withAttachment(index) { a -> if (isThumb) a.copy(thumb = state) else a.copy(upload = state) }
                },
                onProgress = { bytes -> onProgress(base + bytes) },
                chunkBytes = chunkBytes,
            )
            base += file.sizeBytes
        }
        return current
    }

    /** True when the relay no longer has finished upload [file] (purged after an hour unused). */
    private suspend fun gone(api: RelayApi, file: LocalUpload): Boolean {
        val ref = file.ref ?: return true
        return try {
            api.uploadStatus(ref.id); false
        } catch (e: RelayException) {
            if (e.kind != RelayErrorKind.NOT_FOUND) throw e
            true
        }
    }

    private fun failure(item: OutgoingMessage, e: RelayException): ChatOutboxResult = when (e.kind) {
        RelayErrorKind.NETWORK, RelayErrorKind.SERVER, RelayErrorKind.RATE_LIMITED -> ChatOutboxResult.Retry
        else -> {
            remove(item.id)
            ChatOutboxResult.Failed(e)
        }
    }

    private fun persist(id: String, change: (OutgoingMessage) -> OutgoingMessage): OutgoingMessage? {
        var updated: OutgoingMessage? = null
        store.update { list -> list.map { if (it.id == id) change(it).also { u -> updated = u } else it } }
        return updated
    }
}

private fun OutgoingMessage.withAttachment(index: Int, change: (OutgoingAttachment) -> OutgoingAttachment) =
    copy(attachments = attachments.toMutableList().also { it[index] = change(it[index]) })

private fun LocalUpload.restarted() = copy(uploadId = null, ref = null)
