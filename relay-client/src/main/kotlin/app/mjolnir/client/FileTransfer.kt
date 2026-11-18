package app.mjolnir.client

import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.UploadStatus
import app.mjolnir.protocol.safeRelativePath
import app.mjolnir.protocol.topFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

/** One local file to upload, persisted between attempts so an interrupted upload resumes its relay session. */
@Serializable
data class LocalUpload(
    val path: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val uploadId: String? = null,
    val ref: FileRef? = null,
)

private const val MAX_CONFLICTS = 5

/**
 * Uploads [file] in chunks of [chunkBytes], resuming its relay session when [LocalUpload.uploadId] is set.
 * [open] must return a stream positioned at the given offset. [onState] receives every state worth persisting
 * (the new upload id, then the finished ref). [onProgress] receives the number of bytes the relay holds.
 * A local failure to open or read the source (missing file, revoked permission, I/O error) is reported as
 * RelayException(INVALID, Messages.unreadable(name)) so callers give up instead of retrying forever; only relay calls
 * fail with NETWORK/SERVER.
 */
suspend fun uploadResumable(
    api: RelayApi,
    file: LocalUpload,
    open: (offset: Long) -> InputStream,
    onState: (LocalUpload) -> Unit,
    onProgress: (Long) -> Unit = {},
    chunkBytes: Long = Limits.UPLOAD_CHUNK_BYTES,
): FileRef {
    file.ref?.let { return it }
    require(chunkBytes in 1..Limits.UPLOAD_CHUNK_BYTES) { "chunkBytes out of range: $chunkBytes" }
    var state = file

    suspend fun newSession(): UploadStatus {
        val created = api.createUpload(state.relativePath, state.mimeType, state.sizeBytes)
        state = state.copy(uploadId = created.uploadId)
        onState(state)
        return created
    }

    var status: UploadStatus = when (val id = state.uploadId) {
        null -> newSession()
        else -> try {
            api.uploadStatus(id)
        } catch (e: RelayException) {
            if (e.kind != RelayErrorKind.NOT_FOUND) throw e
            newSession() // the session expired or was purged: start over
        }
    }
    val uploadId = status.uploadId
    val size = state.sizeBytes
    var conflicts = 0
    var buf: ByteArray? = null

    while (true) {
        status.file?.let { ref ->
            onProgress(size)
            state = state.copy(ref = ref)
            onState(state)
            return ref
        }
        if (status.offset !in 0 until size) {
            throw RelayException(RelayErrorKind.SERVER, "relay reports offset ${status.offset} of $size")
        }
        onProgress(status.offset)
        val stream = withContext(Dispatchers.IO) { readFailure(state) { open(status.offset) } }
        try {
            var pos = status.offset
            while (true) {
                val len = minOf(chunkBytes, size - pos).toInt()
                val chunk = buf ?: ByteArray(minOf(chunkBytes, size).toInt()).also { buf = it }
                val read = withContext(Dispatchers.IO) { readFailure(state) { stream.readNBytes(chunk, 0, len) } }
                if (read < len) throw RelayException(RelayErrorKind.INVALID, "${state.path} is shorter than $size bytes")
                status = api.uploadChunk(uploadId, pos, size, chunk, len)
                if (status.file != null) break
                if (status.offset != pos + len) {
                    // 409: the relay holds another offset; reopen there.
                    if (++conflicts > MAX_CONFLICTS) {
                        throw RelayException(RelayErrorKind.SERVER, "upload $uploadId keeps conflicting at ${status.offset}")
                    }
                    break
                }
                conflicts = 0
                pos = status.offset
                onProgress(pos)
            }
        } finally {
            runCatching { stream.close() } // plain call: a withContext here would be skipped on cancellation
        }
    }
}

/** Runs a local open/read of [file]'s source; any failure becomes INVALID "Couldn't read <name>". */
private inline fun <T> readFailure(file: LocalUpload, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: RelayException) {
    throw e // a deliberate classification by the caller's open()
} catch (e: Exception) {
    throw RelayException(RelayErrorKind.INVALID, Messages.unreadable(file.relativePath.substringAfterLast('/')), e)
}

/** Where a received file goes: [folder] (null = the save root, else e.g. "Photos (1)/2024") and its [fileName]. */
data class ReceivedPath(val folder: String?, val fileName: String)

/**
 * Downloads every ref into `stagingDir/<id>.part` (resuming existing parts), and only once all of them are present
 * and hash-checked publishes them in order, giving each distinct top folder one unique name via [folderExists].
 * Chosen names are compared case-insensitively ("Photos" and "photos" never share a folder on Windows).
 * [publish] is last so it can be a trailing lambda. Each part is deleted after its publish. On failure the exception is rethrown and parts are kept for resume.
 */
suspend fun <T> receiveFiles(
    api: RelayApi,
    refs: List<FileRef>,
    stagingDir: File,
    folderExists: (String) -> Boolean,
    onProgress: (ref: FileRef, bytes: Long) -> Unit = { _, _ -> },
    publish: (part: File, target: ReceivedPath, ref: FileRef) -> T,
): List<T> {
    withContext(Dispatchers.IO) { stagingDir.mkdirs() }
    val parts = refs.map { ref ->
        val part = File(stagingDir, "${ref.id}.part")
        api.downloadFile(ref, part) { onProgress(ref, it) }
        part
    }
    val paths = refs.map { safeRelativePath(it.relativePath) }
    val folderNames = mutableMapOf<String, String>()
    for (top in paths.mapNotNull(::topFolder).distinct()) {
        folderNames[top] = uniqueName(top, keepExtension = false) { c ->
            folderExists(c) || folderNames.values.any { it.equals(c, ignoreCase = true) }
        }
    }
    return refs.indices.map { i ->
        val segments = paths[i].split('/')
        val folder = if (segments.size < 2) null
        else (listOf(folderNames.getValue(segments.first())) + segments.subList(1, segments.size - 1)).joinToString("/")
        val result = publish(parts[i], ReceivedPath(folder, segments.last()), refs[i])
        withContext(Dispatchers.IO) { parts[i].delete() }
        result
    }
}

/** A file in [dir] named [name], or "name (1).ext", "name (2).ext", … when taken. */
fun uniqueTarget(dir: File, name: String): File = File(dir, uniqueName(name) { File(dir, it).exists() })

/** [name] if free, else the first free "base (n).ext" for n = 1, 2, … ("README" → "README (1)"). */
fun uniqueName(name: String, exists: (String) -> Boolean): String = uniqueName(name, keepExtension = true, exists)

/** Like [uniqueName]; with [keepExtension] false (folder names such as "v1.2") the counter goes at the very end. */
internal fun uniqueName(name: String, keepExtension: Boolean, exists: (String) -> Boolean): String {
    if (!exists(name)) return name
    val dot = if (keepExtension) name.lastIndexOf('.') else -1
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (true) {
        val candidate = "$base ($n)$ext"
        if (!exists(candidate)) return candidate
        n++
    }
}

/** Regular files under [root] (symbolic links not followed), as uploads whose relativePath starts with root's name. */
fun walkFolder(root: File): List<LocalUpload> {
    val rootPath = root.toPath()
    return Files.walk(rootPath).use { stream ->
        stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.map { p ->
            val under = rootPath.relativize(p).joinToString("/") { it.toString() }
            val mime = try { Files.probeContentType(p) } catch (_: Exception) { null }
            LocalUpload(
                path = p.toAbsolutePath().toString(),
                relativePath = "${root.name}/$under",
                mimeType = mime ?: "application/octet-stream",
                sizeBytes = Files.size(p),
            )
        }.toList()
    }.sortedBy { it.relativePath }
}

/** Deletes `*.part` files in [stagingDir] whose id is not in [keepIds]. */
fun cleanStaging(stagingDir: File, keepIds: Set<String>) {
    stagingDir.listFiles()?.forEach { f ->
        if (f.isFile && f.name.endsWith(".part") && f.name.removeSuffix(".part") !in keepIds) f.delete()
    }
}
