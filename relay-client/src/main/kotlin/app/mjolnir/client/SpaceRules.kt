package app.mjolnir.client

import app.mjolnir.protocol.Space

/** True once the relay reports the admin silent: not connected for more than 30 days. */
fun canTakeOver(space: Space): Boolean = space.adminSilent
