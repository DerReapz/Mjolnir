package app.mjolnir.desktop

import app.mjolnir.client.Messages
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.imageio.ImageIO

/** What the preview pane shows for one queued row. */
sealed interface ItemPreview {
    data class Photo(val name: String, val sizeBytes: Long, val width: Int, val height: Int, val path: String) : ItemPreview
    /** [typeLabel] is the upper-cased extension, or `File`. */
    data class OtherFile(val name: String, val sizeBytes: Long, val typeLabel: String, val path: String) : ItemPreview
    data class Folder(val name: String, val files: Int, val sizeBytes: Long, val children: List<FolderChild>, val path: String) : ItemPreview
    data class FolderChild(val name: String, val isDir: Boolean, val files: Int, val sizeBytes: Long)
    data class Link(val url: String, val title: String?, val hostPath: String) : ItemPreview
    data class Missing(val name: String) : ItemPreview
}

/** Reads the disk (header only for images); call off the UI thread. */
fun previewOf(file: File): ItemPreview = when {
    file.isDirectory -> folderPreview(file)
    file.isFile -> imageSize(file)?.let { (w, h) -> ItemPreview.Photo(file.name, file.length(), w, h, file.path) }
        ?: ItemPreview.OtherFile(file.name, file.length(), file.extension.uppercase().ifEmpty { "File" }, file.path)
    else -> ItemPreview.Missing(file.name)
}

fun previewOfLink(url: String, title: String?): ItemPreview.Link = ItemPreview.Link(url, title, hostPath(url))

fun detailLine(p: ItemPreview): String = when (p) {
    is ItemPreview.Photo -> "Photo · ${Messages.size(p.sizeBytes)} · ${p.width} × ${p.height}"
    is ItemPreview.OtherFile -> "${p.typeLabel} · ${Messages.size(p.sizeBytes)}"
    is ItemPreview.Folder -> "Folder · ${p.files} ${if (p.files == 1) "file" else "files"} · ${Messages.size(p.sizeBytes)}"
    is ItemPreview.Link -> "Link · ${p.hostPath}"
    is ItemPreview.Missing -> "Missing"
}

/** Width and height from the image header, or null when no reader recognizes the file. */
private fun imageSize(file: File): Pair<Int, Int>? = try {
    ImageIO.createImageInputStream(file)?.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return@use null
        val reader = readers.next()
        try {
            reader.input = input
            reader.getWidth(0) to reader.getHeight(0)
        } finally {
            reader.dispose()
        }
    }
} catch (_: Exception) {
    null
}

private fun folderPreview(root: File): ItemPreview.Folder {
    val children = (root.listFiles() ?: emptyArray()).map { c ->
        if (c.isDirectory) {
            val (n, size) = countFiles(c)
            ItemPreview.FolderChild(c.name, true, n, size)
        } else {
            ItemPreview.FolderChild(c.name, false, 1, c.length())
        }
    }.sortedWith(compareByDescending<ItemPreview.FolderChild> { it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    return ItemPreview.Folder(root.name, children.sumOf { it.files }, children.sumOf { it.sizeBytes }, children, root.path)
}

private fun countFiles(dir: File): Pair<Int, Long> = try {
    Files.walk(dir.toPath()).use { s ->
        val files = s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        files.size to files.sumOf { Files.size(it) }
    }
} catch (_: Exception) {
    0 to 0L
}

/** `host/path` without scheme, query or trailing slash, percent-escapes decoded. */
private fun hostPath(url: String): String = try {
    val u = URI(url)
    val host = u.host
    if (host == null) throw IllegalArgumentException()
    (host + (u.path ?: "")).trimEnd('/')
} catch (_: Exception) {
    url.substringAfter("://").substringBefore('?').substringBefore('#').trimEnd('/')
}
