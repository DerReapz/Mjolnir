package app.mjolnir.desktop

import app.mjolnir.client.DestinationGroup
import app.mjolnir.client.DestinationKey
import app.mjolnir.client.LocalUpload
import app.mjolnir.client.Messages
import app.mjolnir.client.destinationKey
import app.mjolnir.client.groupDestinations
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
    val targets: List<Peer> = emptyList(),
    /** The chosen card: a device in the context of one space (null: a paired device). */
    val targetKey: DestinationKey? = null,
    val files: List<LocalUpload> = emptyList(),
    val notice: String? = null,
    /** The destination used last; sorts first in [groups]. */
    val lastUsedId: String? = null,
    /** Key of the row shown in the preview: a URL or a top-level path (see [uploadUnitKey]). */
    val selectedRow: String? = null,
    /** True while [AppController.sendSelected] runs; a second send is refused until it ends. */
    val sending: Boolean = false,
) {
    val groups: List<DestinationGroup> get() = groups { it.name }

    /** [targets] grouped and ordered by the name [nameOf] shows (the nickname, when there is one). */
    fun groups(nameOf: (Peer) -> String): List<DestinationGroup> = groupDestinations(targets, lastUsedId, nameOf)

    fun selectRow(key: String): SendState = copy(selectedRow = key)

    /** E.g. `3 of 4 selected · 1.4 GB`: queued links and top-level files/folders (rows keyed by URL or top-level path; size counts selected files). */
    val summary: String get() {
        val units = files.groupBy(::uploadUnitKey).keys
        val count = links.count { it in selected } + units.count { it in selected }
        return "$count of ${links.size + units.size} selected · ${Messages.size(selectedFiles.sumOf { it.sizeBytes })}"
    }

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
        val added = result.drop(files.size).mapTo(HashSet()) { uploadUnitKey(it) }
        return copy(files = result, selected = selected + added, notice = notice)
    }

    fun removeFile(path: String): SendState {
        val left = files.filter { it.path != path }
        return copy(files = left, selected = selected.intersect(links.toSet() + left.map(::uploadUnitKey))).withoutStaleRow()
    }

    /** Clears [selectedRow] when the row it points at is no longer queued. */
    fun withoutStaleRow(): SendState =
        if (selectedRow == null || selectedRow in links || files.any { uploadUnitKey(it) == selectedRow }) this else copy(selectedRow = null)

    fun withText(text: String): SendState {
        val links = extractUrls(text)
        return copy(text = text, links = links, selected = links.toSet() + selected.intersect(files.mapTo(HashSet()) { uploadUnitKey(it) })).withoutStaleRow()
    }

    /** Drops [urls] from the pasted text and the link list; the check marks of the remaining links are kept. */
    fun withoutLinks(urls: Set<String>): SendState {
        if (urls.isEmpty()) return this
        // Drop the sent URLs as whole tokens, using extractUrls' own trimming rules (quotes, brackets, balanced parens),
        // so they are not found again later. Lines without a sent URL stay exactly as typed.
        val kept = text.split("\n").mapNotNull { line ->
            if (extractUrls(line).none { it in urls }) return@mapNotNull line
            line.split(Regex("\\s+")).mapNotNull { token -> withoutSent(token, urls) }.joinToString(" ").ifEmpty { null }
        }.joinToString("\n")
        val left = links - urls
        return copy(text = kept, links = left, selected = selected - urls).withoutStaleRow()
    }

    fun toggle(url: String): SendState = copy(selected = if (url in selected) selected - url else selected + url)

    /** The chosen device; its cards under every heading are highlighted (see [isSelected]). */
    val targetId: String? get() = targetKey?.deviceId

    /**
     * Target: the current card if still listed, else the card of [lastTargetId] (its *My devices* card when it has one),
     * else the first card (PC or phone), else null.
     */
    fun withTargets(peers: List<Peer>, lastTargetId: String?): SendState {
        val keys = peers.map { it.destinationKey }
        val last = lastTargetId?.let { id -> keys.firstOrNull { it.deviceId == id && it.spaceId == null } ?: keys.firstOrNull { it.deviceId == id } }
        val target = targetKey?.takeIf { it in keys } ?: last ?: keys.firstOrNull()
        return copy(targets = peers, targetKey = target, lastUsedId = lastTargetId)
    }

    /** Selects the card [key] (a device under one heading); unknown cards change nothing. */
    fun selectTarget(key: DestinationKey): SendState = if (targets.any { it.destinationKey == key }) copy(targetKey = key) else this

    /** Selects [id]'s *My devices* card, else its first card. */
    fun selectTarget(id: String): SendState {
        val cards = targets.filter { it.deviceId == id }.map { it.destinationKey }
        return (cards.firstOrNull { it.spaceId == null } ?: cards.firstOrNull())?.let(::selectTarget) ?: this
    }

    /** True for every card of the chosen device, whichever heading it sits under. */
    fun isSelected(peer: Peer): Boolean = peer.deviceId == targetKey?.deviceId

    /** Files and folders whose row ([uploadUnitKey]) is checked. */
    val selectedFiles: List<LocalUpload> get() = files.filter { uploadUnitKey(it) in selected }

    val canSend: Boolean get() = targetKey != null && (links.any { it in selected } || selectedFiles.isNotEmpty())

    fun tabs(): List<Tab> = links.filter { it in selected }.map { Tab(it) }

    /** The chosen card's peer; its `space` is the space the send is made in. */
    val target: Peer? get() = targets.find { it.destinationKey == targetKey }
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

private const val DROPPABLE = "\"'<>[](){}.,;:!?"

/** [token] without its sent URLs; null when nothing but quotes, brackets or punctuation is left. */
private fun withoutSent(token: String, urls: Set<String>): String? {
    var t = token
    for (u in extractUrls(token)) if (u in urls) t = t.replaceFirst(u, "")
    return if (t != token && t.all { it in DROPPABLE }) null else t
}
