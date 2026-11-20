package app.mjolnir.client

import app.mjolnir.protocol.Peer

data class DestinationGroup(val key: String, val title: String?, val peers: List<Peer>)

/** Groups send destinations; today only "mine", titled only once there is more than one group. Last-used first, then by the name [nameOf] shows (the real name unless the caller applies nicknames). */
fun groupDestinations(peers: List<Peer>, lastUsedId: String?, nameOf: (Peer) -> String = { it.name }): List<DestinationGroup> {
    val sorted = peers.sortedWith(
        compareBy<Peer> { it.deviceId != lastUsedId }.thenBy(String.CASE_INSENSITIVE_ORDER) { nameOf(it) },
    )
    val groups = listOf(DestinationGroup("mine", null, sorted))
    return if (groups.size == 1) groups else groups.map { if (it.key == "mine") it.copy(title = "My devices") else it }
}
