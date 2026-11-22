package app.mjolnir.client

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * How far clockwise an MP4/MOV video must be turned to show upright: 0, 90, 180 or 270, from the display matrix of its
 * video track's `tkhd` box (phones record portrait video as landscape frames plus this flag). 0 when the file has no
 * such box, isn't an MP4/MOV, or can't be read. Only box headers and the `moov` box are read, never the media data.
 */
fun videoRotation(file: File): Int = try {
    RandomAccessFile(file, "r").use { f -> Mp4Boxes(f).rotation() }
} catch (_: Exception) {
    0
}

private class Mp4Boxes(private val f: RandomAccessFile) {
    private class Box(val type: String, val bodyStart: Long, val end: Long)

    /** The boxes directly inside [start]..[end]. */
    private fun children(start: Long, end: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = start
        while (pos + 8 <= end && out.size < MAX_BOXES) {
            f.seek(pos)
            var size = f.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) { size = f.readLong(); header = 16 } // a 64-bit size follows
            else if (size == 0L) size = end - pos // to the end of the enclosing box
            if (size < header || pos + size > end) break // corrupt
            out += Box(type, pos + header, pos + size)
            pos += size
        }
        return out
    }

    fun rotation(): Int {
        val moov = children(0, f.length()).firstOrNull { it.type == "moov" } ?: return 0
        val traks = children(moov.bodyStart, moov.end).filter { it.type == "trak" }
        // The video track ("vide" handler); else the first track with a header.
        val headers = traks.mapNotNull { trak ->
            val boxes = children(trak.bodyStart, trak.end)
            val tkhd = boxes.firstOrNull { it.type == "tkhd" } ?: return@mapNotNull null
            tkhd to (handler(boxes) == "vide")
        }
        val tkhd = (headers.firstOrNull { it.second } ?: headers.firstOrNull())?.first ?: return 0
        return matrixRotation(tkhd)
    }

    private fun handler(trakBoxes: List<Box>): String? {
        val mdia = trakBoxes.firstOrNull { it.type == "mdia" } ?: return null
        val hdlr = children(mdia.bodyStart, mdia.end).firstOrNull { it.type == "hdlr" } ?: return null
        if (hdlr.end - hdlr.bodyStart < 12) return null
        f.seek(hdlr.bodyStart + 8) // version/flags, pre_defined
        return ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
    }

    /** The matrix's a, b (16.16 fixed point) give the angle; only right angles count. */
    private fun matrixRotation(tkhd: Box): Int {
        f.seek(tkhd.bodyStart)
        val version = f.readUnsignedByte()
        // version/flags (4), times, track id, reserved, duration: 20 bytes (v0) or 32 (v1); then 16 more before the matrix.
        val matrixAt = tkhd.bodyStart + 4 + (if (version == 1) 32 else 20) + 16
        if (matrixAt + 36 > tkhd.end) return 0
        f.seek(matrixAt)
        val a = f.readInt() / 65536.0
        val b = f.readInt() / 65536.0
        val degrees = Math.toDegrees(atan2(b, a)).roundToInt()
        return when (((degrees % 360) + 360) % 360) {
            90 -> 90; 180 -> 180; 270 -> 270; else -> 0
        }
    }

    private companion object {
        const val MAX_BOXES = 4096
    }
}
