package app.mjolnir.client

import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.Space

/** True once the admin has not connected to the relay for more than 30 days. */
fun canTakeOver(space: Space, nowMs: Long): Boolean = nowMs - space.adminLastSeenEpochMs > Limits.ADMIN_SILENT_TAKEOVER_MS
