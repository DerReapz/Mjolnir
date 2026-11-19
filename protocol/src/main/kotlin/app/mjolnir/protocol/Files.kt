package app.mjolnir.protocol

private val RESERVED = Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")

fun safeFileName(name: String): String {
    var s = name.substringAfterLast('/').substringAfterLast('\\')
    s = buildString {
        for (c in s) append(if (c in "<>:\"/\\|?*" || c.isISOControl() ||
                c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069') '_' else c)
    }
    s = s.trimStart(' ').trimEnd('.', ' ')
    if (RESERVED.matches(s.substringBefore('.'))) s = "_$s"
    if (s.isEmpty()) return "file"
    if (s.length > Limits.MAX_FILE_NAME_LENGTH) {
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0) s.substring(dot) else ""
        s = if (ext.length < Limits.MAX_FILE_NAME_LENGTH) {
            s.substring(0, Limits.MAX_FILE_NAME_LENGTH - ext.length) + ext
        } else s.take(Limits.MAX_FILE_NAME_LENGTH)
    }
    // Storage layers (MediaStore, ext4, NTFS via some tools) cap names in bytes: long CJK or emoji names must fit too.
    if (utf8Length(s) > MAX_FILE_NAME_BYTES) {
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0) s.substring(dot) else ""
        val extBytes = utf8Length(ext)
        s = if (extBytes < MAX_FILE_NAME_BYTES / 2) {
            takeUtf8(s.substring(0, dot.takeIf { it > 0 } ?: s.length), MAX_FILE_NAME_BYTES - extBytes) + ext
        } else takeUtf8(s, MAX_FILE_NAME_BYTES)
    }
    return s
}

/** Upper bound for a received file name's UTF-8 length (most file systems allow 255 bytes). */
const val MAX_FILE_NAME_BYTES = 240

private fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size

/** The longest prefix of [s] made of whole code points whose UTF-8 form is at most [maxBytes] bytes. */
private fun takeUtf8(s: String, maxBytes: Int): String {
    var bytes = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
        if (bytes + n > maxBytes) break
        bytes += n
        i += Character.charCount(cp)
    }
    return s.substring(0, i)
}

fun safeRelativePath(path: String): String {
    var parts = path.split('/', '\\')
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .map { safeFileName(it) }
        .takeLast(Limits.MAX_PATH_DEPTH)
    while (parts.size > 1 && parts.joinToString("/").length > Limits.MAX_RELATIVE_PATH_LENGTH) {
        parts = parts.drop(1)
    }
    return if (parts.isEmpty()) "file" else parts.joinToString("/")
}

fun topFolder(relativePath: String): String? {
    val parts = relativePath.split('/')
    return if (parts.size >= 2) parts.first() else null
}
