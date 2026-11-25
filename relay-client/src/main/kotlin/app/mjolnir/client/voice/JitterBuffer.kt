package app.mjolnir.client.voice

/**
 * One speaker's packets in sequence order, played out one 20 ms frame per [pop]. It fills to [targetMs] before playing,
 * reorders within that, drops packets older than the play point, and skips ahead to the newest [targetMs] when more
 * than [maxMs] is queued. Sequence numbers are u16 and wrap. A speaker that stops sending goes back to filling once
 * [maxMs] has passed with nothing to play, so the next talk spurt starts fresh. Not thread-safe.
 */
class JitterBuffer(targetMs: Int = 60, maxMs: Int = 200) {
    private val targetFrames = (targetMs / OpusCodec.FRAME_MS).coerceAtLeast(1)
    private val maxFrames = (maxMs / OpusCodec.FRAME_MS).coerceAtLeast(targetFrames)
    private val packets = HashMap<Int, ByteArray>()

    /** The next sequence to play while [active]; while filling, the oldest packet queued. */
    private var head = 0
    private var newest = 0
    private var emptyPops = 0

    /** True while playing out (filled once and not idle since); false while filling. */
    var active = false
        private set

    fun push(seq: Int, packet: ByteArray) {
        val s = seq and 0xFFFF
        if (packets.isEmpty() && !active) {
            head = s; newest = s
        } else {
            if (diff(s, head) < 0) {
                if (active) return // late: its time has passed
                head = s // filling: an earlier packet arrived after a later one
            }
            if (diff(s, newest) > 0) newest = s
        }
        packets[s] = packet
        emptyPops = 0
        // Too much queued: keep only the newest target's worth.
        if (diff(newest, head) + 1 > maxFrames) {
            val newHead = (newest - targetFrames + 1) and 0xFFFF
            packets.keys.removeAll { diff(it, newHead) < 0 }
            head = newHead
        }
    }

    /** The frame due now: its packet, or null for a gap (or while filling; see [active]). */
    fun pop(): ByteArray? {
        if (!active) {
            if (packets.isEmpty() || diff(newest, head) + 1 < targetFrames) return null
            active = true
        }
        val packet = packets.remove(head)
        head = (head + 1) and 0xFFFF
        if (packets.isEmpty()) {
            // Nothing queued: the speaker went quiet. After maxMs of that, fill afresh for the next spurt.
            if (++emptyPops > maxFrames) { active = false; emptyPops = 0 }
            newest = head
        }
        return packet
    }

    /** The packet the next [pop] would return, if here (after a gap, the one whose FEC can rebuild it). */
    fun peek(): ByteArray? = if (active) packets[head] else null

    /** Signed distance from [b] to [a] in u16 sequence space. */
    private fun diff(a: Int, b: Int): Int = ((a - b) and 0xFFFF).let { if (it >= 0x8000) it - 0x10000 else it }
}
