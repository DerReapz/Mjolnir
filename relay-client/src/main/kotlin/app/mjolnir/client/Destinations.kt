package app.mjolnir.client

import app.mjolnir.protocol.Peer

data class DestinationGroup(val key: String, val title: String?, val peers: List<Peer>)

/** What a destination card selects: a device in the context of one space (null: a paired device). A device in two spaces has two cards. */
data class DestinationKey(val deviceId: String, val spaceId: String?)

val Peer.destinationKey: DestinationKey get() = DestinationKey(deviceId, space?.id)

/**
 * Groups send destinations: "mine" (paired devices; titled "My devices" once there is more than one group), then one
 * group per space, keyed "space:{id}" and titled with the space name, in the order the spaces first appear in [peers]
 * (the relay lists them in creation order). Empty groups are dropped. Within a group: last-used first, then by the name
 * [nameOf] shows (the real name unless the caller applies nicknames).
 */
fun groupDestinations(peers: List<Peer>, lastUsedId: String?, nameOf: (Peer) -> String = { it.name }): List<DestinationGroup> {
    fun sorted(list: List<Peer>) = list.sortedWith(
        compareBy<Peer> { it.deviceId != lastUsedId }.thenBy(String.CASE_INSENSITIVE_ORDER) { nameOf(it) },
    )
    val bySpace = peers.filter { it.space != null }.groupBy { it.space!!.id } // keeps first-appearance order
    val spaceGroups = bySpace.map { (id, members) -> DestinationGroup("space:$id", members.first().space!!.name, sorted(members)) }
    val mine = DestinationGroup("mine", null, sorted(peers.filter { it.space == null }))
    val groups = (listOf(mine) + spaceGroups).filter { it.peers.isNotEmpty() }
    return if (groups.size > 1) groups.map { if (it.key == "mine") it.copy(title = "My devices") else it } else groups
}
