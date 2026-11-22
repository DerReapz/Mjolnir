package app.mjolnir.client

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoRotationTest {
    private val dir = createTempDirectory("rotation").toFile()

    private fun resource(name: String): File =
        File(dir, name).apply { writeBytes(VideoRotationTest::class.java.getResource("/$name")!!.readBytes()) }

    /** Made by `ffmpeg -display_rotation 270 -i tiny.mp4 -c copy` (ffprobe: rotation -90): a phone's portrait flag. */
    @Test fun ffmpegPortraitClipIsTurned90() {
        assertEquals(90, videoRotation(resource("portrait-rotate90.mp4")))
        assertEquals(0, videoRotation(resource("upright.mp4")))
    }

    private fun box(type: String, body: ByteArray, largeSize: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream(); val d = DataOutputStream(out)
        if (largeSize) { d.writeInt(1); d.writeBytes(type); d.writeLong(16L + body.size) }
        else { d.writeInt(8 + body.size); d.writeBytes(type) }
        d.write(body)
        return out.toByteArray()
    }

    /** A tkhd whose matrix starts a, b, u, c, d (16.16 fixed). */
    private fun tkhd(version: Int, a: Int, b: Int, c: Int, d: Int): ByteArray {
        val out = ByteArrayOutputStream(); val s = DataOutputStream(out)
        s.writeInt(version shl 24)
        repeat(if (version == 1) 32 else 20) { s.writeByte(0) }
        repeat(16) { s.writeByte(0) }
        for (v in intArrayOf(a shl 16, b shl 16, 0, c shl 16, d shl 16, 0, 0, 0, 0x40000000)) s.writeInt(v)
        s.writeInt(64 shl 16); s.writeInt(36 shl 16)
        return box("tkhd", out.toByteArray())
    }

    private fun hdlr(kind: String) = box("hdlr", ByteArray(8) + kind.toByteArray() + ByteArray(12))

    private fun trak(tkhd: ByteArray, kind: String) = box("trak", tkhd + box("mdia", hdlr(kind)))

    private fun file(vararg top: ByteArray) = File.createTempFile("video", ".mp4", dir).apply { writeBytes(top.reduce { x, y -> x + y }) }

    @Test fun handBuiltMatrices() {
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(4))
        val mdat = box("mdat", ByteArray(100), largeSize = true)
        fun rotationOf(vararg traks: ByteArray) = videoRotation(file(ftyp, mdat, box("moov", traks.reduce { x, y -> x + y })))
        assertEquals(0, rotationOf(trak(tkhd(0, 1, 0, 0, 1), "vide")))
        assertEquals(90, rotationOf(trak(tkhd(0, 0, 1, -1, 0), "vide")))
        assertEquals(180, rotationOf(trak(tkhd(1, -1, 0, 0, -1), "vide"))) // version 1 header
        assertEquals(270, rotationOf(trak(tkhd(0, 0, -1, 1, 0), "vide")))
        // The video track's matrix counts, not the sound track's before it.
        assertEquals(90, rotationOf(trak(tkhd(0, -1, 0, 0, -1), "soun"), trak(tkhd(0, 0, 1, -1, 0), "vide")))
    }

    @Test fun notAnMp4IsUpright() {
        assertEquals(0, videoRotation(file("not a video at all".toByteArray())))
        assertEquals(0, videoRotation(file(box("moov", ByteArray(3)))))
        assertEquals(0, videoRotation(File(dir, "missing.mp4")))
        // A box claiming more than the file holds.
        assertEquals(0, videoRotation(file(byteArrayOf(0x7F, 0, 0, 0) + "moov".toByteArray())))
    }
}
