package app.mjolnir.server

import app.mjolnir.protocol.safeRelativePath
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Entry names for [paths], in order: each a safe relative path (no `..`, no root, no reserved names), and unique
 * ignoring case (so a zip unpacks fully on Windows and macOS too): a repeat gets " (2)", " (3)"… before its extension.
 */
fun zipEntryNames(paths: List<String>): List<String> {
    val used = HashSet<String>()
    return paths.map { raw ->
        val safe = safeRelativePath(raw)
        var name = safe
        var n = 2
        while (!used.add(name.lowercase())) {
            val dir = safe.substringBeforeLast('/', "")
            val file = safe.substringAfterLast('/')
            val dot = file.lastIndexOf('.')
            val numbered = if (dot > 0) "${file.substring(0, dot)} ($n)${file.substring(dot)}" else "$file ($n)"
            name = if (dir.isEmpty()) numbered else "$dir/$numbered"
            n++
        }
        name
    }
}

/**
 * Writes [entries] (entry name to blob) as a zip to [out]. Entries are DEFLATED at level 0: nothing is recompressed,
 * and unlike STORED entries no CRC has to be known before the data is written, so blobs stream straight through.
 */
fun writeZip(out: OutputStream, entries: List<Pair<String, File>>) {
    val zip = ZipOutputStream(out)
    zip.setMethod(ZipOutputStream.DEFLATED)
    zip.setLevel(Deflater.NO_COMPRESSION)
    val buf = ByteArray(64 * 1024)
    for ((name, file) in entries) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zip.write(buf, 0, n)
            }
        }
        zip.closeEntry()
    }
    zip.finish()
    zip.flush()
}
