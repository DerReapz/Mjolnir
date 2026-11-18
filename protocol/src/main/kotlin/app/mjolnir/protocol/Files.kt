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
    return s
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
