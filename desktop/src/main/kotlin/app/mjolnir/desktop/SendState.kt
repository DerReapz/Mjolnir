package app.mjolnir.desktop

import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Messages
import app.mjolnir.client.walkFolder
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.extractUrls
import java.io.File
import java.nio.file.Files

/** Everything the Send window shows; pure so it can be tested without a UI. */
data class SendState(
    val text: String = "",
    val links: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val phones: List<Peer> = emptyList(),
    val targetId: String? = null,
    val files: List<LocalUpload> = emptyList(),
    val notice: String? = null,
) {
    /**
     * Files become one upload each (relativePath = name); folders are walked. Duplicates (same path) are skipped,
     * files over MAX_FILE_BYTES are left out, and a folder over MAX_FILES_PER_SEND files is refused whole.
     */
    fun withFiles(added: List<File>): SendState {
        val known = files.mapTo(HashSet()) { it.path }
        val result = files.toMutableList()
        var leftOut = 0
        var notice: String? = null
        for (f in added) {
            val uploads = if (f.isDirectory) {
                val walked = try { walkFolder(f) } catch (e: Exception) { notice = Messages.unreadable(f.name); continue }
                if (walked.size > Limits.MAX_FILES_PER_SEND) { notice = Messages.tooManyFiles(f.name); continue }
                if (walked.isEmpty()) { notice = Messages.emptyFolder(f.name); continue }
                walked
            } else if (f.isFile) {
                listOf(singleUpload(f))
            } else {
                notice = Messages.unreadable(f.name); continue
            }
            for (u in uploads) {
                if (u.path in known) continue
                if (u.sizeBytes > Limits.MAX_FILE_BYTES) { leftOut++; continue }
                known += u.path
                result += u
            }
        }
        if (leftOut > 0) {
            notice = if (added.size == 1 && added.single().isFile) Messages.tooBig(added.single().name) else Messages.leftOut(leftOut)
        }
        return copy(files = result, notice = notice)
    }

    fun removeFile(path: String): SendState = copy(files = files.filter { it.path != path })

    fun withText(text: String): SendState {
        val links = extractUrls(text)
        return copy(text = text, links = links, selected = links.toSet())
    }

    fun toggle(url: String): SendState = copy(selected = if (url in selected) selected - url else selected + url)

    /** Target: the current or [lastPhoneId] if still paired, else the first phone, else null. */
    fun withPhones(phones: List<Peer>, lastPhoneId: String?): SendState {
        val list = phones.filter { it.kind == DeviceKind.PHONE }
        val ids = list.map { it.deviceId }
        val target = targetId?.takeIf { it in ids } ?: lastPhoneId?.takeIf { it in ids } ?: ids.firstOrNull()
        return copy(phones = list, targetId = target)
    }

    fun selectTarget(id: String): SendState = if (phones.any { it.deviceId == id }) copy(targetId = id) else this

    val canSend: Boolean get() = targetId != null && (selected.isNotEmpty() || files.isNotEmpty())

    fun tabs(): List<Tab> = links.filter { it in selected }.map { Tab(it) }

    val target: Peer? get() = phones.find { it.deviceId == targetId }
}

private fun singleUpload(f: File): LocalUpload {
    val path = f.absoluteFile.toPath()
    val mime = try { Files.probeContentType(path) } catch (_: Exception) { null }
    return LocalUpload(path.toString(), f.name, mime ?: "application/octet-stream", f.length())
}

/**
 * Splits [files] into groups of at most [max] for separate sends, keeping each top folder together
 * (a folder alone is at most [max] files, which [SendState.withFiles] guarantees).
 */
fun groupForSending(files: List<LocalUpload>, max: Int = Limits.MAX_FILES_PER_SEND): List<List<LocalUpload>> {
    val units = files.groupBy { app.mjolnir.protocol.topFolder(it.relativePath) ?: "\u0000" + it.path }.values
    val groups = mutableListOf<MutableList<LocalUpload>>()
    for (unit in units) {
        val last = groups.lastOrNull()
        if (last != null && last.size + unit.size <= max) last += unit else groups += unit.toMutableList()
    }
    return groups
}
