package app.mjolnir.protocol

object Versions {
    /** `"1.0.10"` to `[1, 0, 10]`; null when any dot-separated part is not a non-negative integer. */
    fun parse(v: String): List<Int>? {
        val parts = v.trim().split('.')
        val nums = parts.map { p -> if (p.isNotEmpty() && p.all { it in '0'..'9' }) p.toIntOrNull() else null }
        return if (nums.any { it == null }) null else nums.filterNotNull()
    }

    /** True when [candidate] is strictly newer than [current]; an unparsable version is never newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
