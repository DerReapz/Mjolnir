package app.mjolnir.desktop

import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Messages
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Tab
import app.mjolnir.protocol.extractUrls
import app.mjolnir.protocol.topFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

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
     * Does disk I/O: the UI calls [scanFiles] off the UI thread and then [withScanned].
     */
    fun withFiles(added: List<File>): SendState = withScanned(scanFiles(added))

    /** Merges the result of [scanFiles] (no I/O). */
    fun withScanned(scan: List<ScannedItem>): SendState {
        val known = files.mapTo(HashSet()) { it.path }
        val result = files.toMutableList()
        var leftOut = 0
        var notice: String? = null
        for (item in scan) {
            if (item.refusal != null) { notice = item.refusal; continue }
            for (u in item.uploads) {
                if (u.path in known) continue
                if (u.sizeBytes > Limits.MAX_FILE_BYTES) { leftOut++; continue }
                known += u.path
                result += u
            }
        }
        if (leftOut > 0) {
            val single = scan.singleOrNull()
            notice = if (single != null && single.isFile) Messages.tooBig(single.name) else Messages.leftOut(leftOut)
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

/** One added file or folder after reading the disk: its uploads, or why it was refused. */
data class ScannedItem(val name: String, val isFile: Boolean, val uploads: List<LocalUpload>, val refusal: String? = null)

/** Reads [added] from disk. A folder walk stops as soon as it finds more than [maxFiles] files. */
fun scanFiles(added: List<File>, maxFiles: Int = Limits.MAX_FILES_PER_SEND): List<ScannedItem> = added.map { f ->
    when {
        f.isDirectory -> try {
            val walked = walkFolderLimited(f, maxFiles)
            when {
                walked == null -> ScannedItem(f.name, false, emptyList(), Messages.tooManyFiles(f.name))
                walked.isEmpty() -> ScannedItem(f.name, false, emptyList(), Messages.emptyFolder(f.name))
                else -> ScannedItem(f.name, false, walked)
            }
        } catch (e: Exception) {
            ScannedItem(f.name, false, emptyList(), Messages.unreadable(f.name))
        }
        f.isFile -> ScannedItem(f.name, true, listOf(singleUpload(f)))
        else -> ScannedItem(f.name, false, emptyList(), Messages.unreadable(f.name))
    }
}

/** Like [app.mjolnir.client.walkFolder], but returns null as soon as there are more than [max] files. */
fun walkFolderLimited(root: File, max: Int): List<LocalUpload>? {
    val rootPath = root.absoluteFile.toPath()
    val paths = Files.walk(rootPath).use { stream ->
        stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.limit(max.toLong() + 1).toList()
    }
    if (paths.size > max) return null
    return paths.map { p ->
        val under = rootPath.relativize(p).joinToString("/") { it.toString() }
        val mime = try { Files.probeContentType(p) } catch (_: Exception) { null }
        LocalUpload(p.toString(), "${root.name}/$under", mime ?: "application/octet-stream", Files.size(p))
    }.sortedBy { it.relativePath }
}

private fun singleUpload(f: File): LocalUpload {
    val path = f.absoluteFile.toPath()
    val mime = try { Files.probeContentType(path) } catch (_: Exception) { null }
    return LocalUpload(path.toString(), f.name, mime ?: "application/octet-stream", f.length())
}

/**
 * The unit an upload belongs to: the absolute folder it was added from (its walked root), or the file itself.
 * Two different folders with the same name are different units.
 */
fun uploadUnitKey(u: LocalUpload): String {
    val depth = u.relativePath.count { it == '/' }
    var f = File(u.path)
    repeat(depth) { f = f.parentFile ?: return u.path }
    return f.path
}

/**
 * Splits [files] into groups of at most [max] for separate sends. A folder is never split, and two folders with the
 * same top name never share a group (so the receiver keeps them apart, e.g. "Photos" and "Photos (1)").
 */
fun groupForSending(files: List<LocalUpload>, max: Int = Limits.MAX_FILES_PER_SEND): List<List<LocalUpload>> {
    val units = files.groupBy(::uploadUnitKey).values
    val groups = mutableListOf<MutableList<LocalUpload>>()
    for (unit in units) {
        val top = topFolder(unit.first().relativePath)
        val fits = groups.lastOrNull()?.takeIf { g ->
            g.size + unit.size <= max && (top == null || g.none { topFolder(it.relativePath) == top })
        }
        if (fits != null) fits += unit else groups += unit.toMutableList()
    }
    return groups
}
