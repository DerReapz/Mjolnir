package app.mjolnir.client.voice

import kotlinx.coroutines.Deferred

/** One open voice socket (`/v1/voice/{roomId}`). */
interface VoiceLink {
    /** Queues one audio frame (`[seq u16 BE][opus]`); never blocks, a frame that does not fit is dropped. */
    fun send(seq: Int, opus: ByteArray)

    /** Leaves the room for good (the relay removes this device at once, with no grace). */
    fun close()

    /**
     * Completes when the socket is gone: with the relay's reason (`room_full`, `removed`, `room_deleted`, `replaced`,
     * `not_found`) when the relay closed it on purpose, else null (a [close], a dropped link or a failed connect).
     */
    val closeReason: Deferred<String?>
}

/**
 * Opens a link to one room. [onRoster] gets each roster (device ids; an index is a speaker index), [onFrame] each audio
 * frame `(speakerIndex, seq, opus)`. Both are called one at a time from the link's reader and must not block.
 */
fun interface VoiceLinkFactory {
    fun open(onRoster: (List<String>) -> Unit, onFrame: (speakerIndex: Int, seq: Int, opus: ByteArray) -> Unit): VoiceLink
}
