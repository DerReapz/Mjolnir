package app.mjolnir.client

import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space

/** [text] cut to at most [max] code points (never inside a surrogate pair). */
fun capCodePoints(text: String, max: Int): String =
    if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

/** The space's members who could be invited to a chat of [chatMembers]: everyone else but this device. */
fun inviteCandidates(space: Space, chatMembers: List<Peer>, selfId: String?): List<Peer> =
    space.members.filter { m -> m.deviceId != selfId && chatMembers.none { it.deviceId == m.deviceId } }
