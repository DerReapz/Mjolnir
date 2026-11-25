package app.mjolnir.client.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JitterBufferTest {
    private fun p(seq: Int) = byteArrayOf((seq shr 8).toByte(), seq.toByte())
    private fun ByteArray?.seq(): Int? = this?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
    private fun JitterBuffer.put(vararg seqs: Int) = seqs.forEach { push(it, p(it)) }
    private fun JitterBuffer.pops(n: Int) = List(n) { pop().seq() }

    @Test fun inOrder() {
        val b = JitterBuffer()
        b.put(10, 11)
        assertNull(b.pop(), "still filling to the 60 ms target")
        assertFalse(b.active)
        b.put(12)
        assertEquals(listOf(10, 11), b.pops(2))
        assertTrue(b.active)
        b.put(13, 14)
        assertEquals(listOf(12, 13, 14), b.pops(3))
    }

    @Test fun reordersWithinTheTarget() {
        val b = JitterBuffer()
        b.put(1, 0, 2)
        assertEquals(listOf(0, 1), b.pops(2))
        b.put(4, 3)
        assertEquals(listOf(2, 3, 4), b.pops(3))
    }

    @Test fun gapGivesOneNull() {
        val b = JitterBuffer()
        b.put(0, 1, 3, 4)
        assertEquals(listOf(0, 1, null, 3, 4), b.pops(5))
    }

    @Test fun peekShowsTheNextPacketForFec() {
        val b = JitterBuffer()
        b.put(0, 2, 3)
        assertEquals(0, b.pop().seq())
        assertNull(b.pop(), "1 is missing")
        assertEquals(2, b.peek().seq(), "the packet after the gap, for FEC")
        assertEquals(2, b.pop().seq())
    }

    @Test fun latePacketIsDropped() {
        val b = JitterBuffer()
        b.put(0, 1, 2)
        assertEquals(listOf(0, 1), b.pops(2))
        b.put(0) // older than the play point
        b.put(1)
        assertEquals(listOf(2, null), b.pops(2))
    }

    @Test fun overflowBeyond200msSkipsAhead() {
        val b = JitterBuffer()
        b.put(*IntArray(11) { it }) // 220 ms queued
        assertEquals(listOf(8, 9, 10), b.pops(3), "skipped to the target's worth of the newest")
        // While playing: more than 200 ms queued ahead of the play point skips ahead too.
        b.put(*IntArray(11) { 11 + it }) // 11..21
        assertEquals(listOf(19, 20, 21), b.pops(3))
        // Up to 200 ms is kept whole.
        b.put(*IntArray(10) { 22 + it }) // 22..31
        assertEquals((22..31).toList(), b.pops(10))
    }

    @Test fun u16Wraparound() {
        val b = JitterBuffer()
        b.put(65534, 65535, 0)
        b.put(1)
        assertEquals(listOf(65534, 65535, 0, 1), b.pops(4))
        b.put(65535) // late across the wrap
        assertNull(b.pop())
    }

    @Test fun idleSpeakerRebuffers() {
        val b = JitterBuffer()
        b.put(0, 1, 2)
        assertEquals(listOf(0, 1, 2), b.pops(3))
        repeat(20) { b.pop() } // the speaker went quiet: no packets for 400 ms
        assertFalse(b.active)
        b.put(5, 6, 7) // a new talk spurt with an earlier seq than the old play point would have reached
        assertEquals(listOf(5, 6, 7), b.pops(3))
    }
}
