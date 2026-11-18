package app.mjolnir.server

import app.mjolnir.protocol.CreateUploadRequest
import app.mjolnir.protocol.ErrorResponse
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.UploadStatus
import app.mjolnir.protocol.safeRelativePath
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

private val CONTENT_RANGE = Regex("""^bytes (\d{1,18})-(\d{1,18})/(\d{1,18})$""")
private val RANGE_FROM = Regex("""^bytes=(\d{1,18})-$""")

/** True for the chunk-upload route, which bounds its own body to the Content-Range length instead of the JSON limit. */
fun isChunkUpload(method: String, path: String): Boolean =
    method == "PUT" && path.startsWith("/v1/uploads/")

/** Runs every purge rule and deletes the blobs the store hands back. */
suspend fun purge(deps: RelayDeps) {
    val ids = deps.store.purgeExpired(deps.clock.nowMs())
    withContext(Dispatchers.IO) { ids.forEach(deps.files::delete) }
}

fun Route.fileRoutes(deps: RelayDeps) {
    val store = deps.store
    val files = deps.files
    // One PUT at a time per upload, so offsets and completion never race.
    val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    authenticate("device") {
        post("/v1/uploads") {
            val me = call.principal<DevicePrincipal>()!!
            val req = call.receive<CreateUploadRequest>()
            if (req.sizeBytes < 0) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_size"))
            if (req.sizeBytes > files.maxFileBytes) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("file_too_large"))
            }
            val id = newId()
            if (!files.create(id, req.sizeBytes)) {
                return@post call.respond(HttpStatusCode.InsufficientStorage, ErrorResponse("relay_full"))
            }
            val now = deps.clock.nowMs()
            try {
                store.createUpload(id, me.deviceId, safeRelativePath(req.relativePath), req.mimeType, req.sizeBytes, now)
            } catch (e: Exception) {
                files.delete(id)
                throw e
            }
            // An empty file has nothing to PUT, so it is complete on creation.
            val ref = if (req.sizeBytes == 0L) store.completeUpload(id, files.complete(id), now) else null
            call.respond(HttpStatusCode.Created, UploadStatus(id, 0, ref))
        }

        get("/v1/uploads/{id}") {
            val me = call.principal<DevicePrincipal>()!!
            val id = call.parameters["id"]!!
            val up = store.upload(id, me.deviceId)
            if (up != null) return@get call.respond(UploadStatus(id, up.offset))
            // The final chunk's response may have been lost: report the finished file.
            val ref = store.ownFile(id, me.deviceId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
            call.respond(UploadStatus(id, ref.sizeBytes, ref))
        }

        put("/v1/uploads/{id}") {
            val me = call.principal<DevicePrincipal>()!!
            val id = call.parameters["id"]!!
            val up = store.upload(id, me.deviceId)
            if (up == null) {
                val ref = store.ownFile(id, me.deviceId)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
                return@put call.respond(HttpStatusCode.Conflict, UploadStatus(id, ref.sizeBytes, ref))
            }
            val m = call.request.header(HttpHeaders.ContentRange)?.let { CONTENT_RANGE.matchEntire(it.trim()) }
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_content_range"))
            val (s, e, t) = m.destructured.toList().map { it.toLong() }
            val length = e - s + 1
            if (t != up.size || e < s || e >= t || length > Limits.UPLOAD_CHUNK_BYTES) {
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_content_range"))
            }
            val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (declared != null && declared != length) {
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_content_length"))
            }
            if (!inFlight.add(id)) return@put call.respond(HttpStatusCode.Conflict, UploadStatus(id, up.offset))
            try {
                val current = store.upload(id, me.deviceId)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
                if (s != current.offset) return@put call.respond(HttpStatusCode.Conflict, UploadStatus(id, current.offset))
                val offset = files.append(id, s, call.receiveChannel(), length)
                if (offset < up.size) {
                    store.setUploadOffset(id, offset)
                    return@put call.respond(UploadStatus(id, offset))
                }
                val sha = withContext(Dispatchers.IO) { files.complete(id) }
                val ref = store.completeUpload(id, sha, deps.clock.nowMs())
                call.respond(UploadStatus(id, offset, ref))
            } finally {
                inFlight.remove(id)
            }
        }

        get("/v1/files/{id}") {
            val me = call.principal<DevicePrincipal>()!!
            val id = call.parameters["id"]!!
            val ref = store.fileForDownload(id, me.deviceId)
            val blob = files.file(id)
            if (ref == null || !blob.isFile) return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
            val size = ref.sizeBytes
            call.response.header(HttpHeaders.ETag, "\"${ref.sha256}\"")
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            // Only `bytes=N-` is honored; any other Range form is ignored and the whole file is sent.
            val from = call.request.header(HttpHeaders.Range)?.let { RANGE_FROM.matchEntire(it.trim()) }
                ?.groupValues?.get(1)?.toLong()
            if (from != null && from >= size) {
                call.response.header(HttpHeaders.ContentRange, "bytes */$size")
                return@get call.respond(HttpStatusCode.RequestedRangeNotSatisfiable, ErrorResponse("range_not_satisfiable"))
            }
            val start = from ?: 0L
            val status = if (from != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
            if (from != null) call.response.header(HttpHeaders.ContentRange, "bytes $from-${size - 1}/$size")
            call.respondBytesWriter(ContentType.Application.OctetStream, status, size - start) {
                RandomAccessFile(blob, "r").use { raf ->
                    val ch = raf.channel
                    val buf = java.nio.ByteBuffer.allocate(64 * 1024)
                    var pos = start
                    while (pos < size) {
                        buf.clear()
                        val n = withContext(Dispatchers.IO) { ch.read(buf, pos) }
                        if (n <= 0) break
                        writeFully(buf.array(), 0, n)
                        pos += n
                    }
                }
            }
        }
    }
}
