package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.Peer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

data class DeviceRow(val id: String, val name: String, val kind: DeviceKind, val pushToken: String?)

data class PairingRow(
    val id: String,
    val code: String,
    val ownerId: String,
    val claimerId: String?,
    val state: PairingState,
    val expiresAtMs: Long,
)

fun DeviceRow.toPeer(): Peer = Peer(id, name, kind)

class Store(jdbcUrl: String) {
    private val conn: Connection = DriverManager.getConnection(jdbcUrl)

    init {
        conn.createStatement().use {
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS devices(
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    push_token TEXT NULL,
                    created_at INTEGER NOT NULL)"""
            )
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS pairings(
                    id TEXT PRIMARY KEY,
                    code TEXT NOT NULL,
                    owner_id TEXT NOT NULL,
                    claimer_id TEXT NULL,
                    state TEXT NOT NULL,
                    expires_at INTEGER NOT NULL)"""
            )
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS links(
                    a TEXT NOT NULL,
                    b TEXT NOT NULL,
                    PRIMARY KEY(a, b))"""
            )
        }
    }

    private fun ResultSet.toPairing() = PairingRow(
        getString("id"), getString("code"), getString("owner_id"), getString("claimer_id"),
        PairingState.valueOf(getString("state")), getLong("expires_at"),
    )

    private fun ResultSet.toDevice() =
        DeviceRow(getString("id"), getString("name"), DeviceKind.valueOf(getString("kind")), getString("push_token"))

    @Synchronized
    fun createDevice(name: String, kind: DeviceKind, tokenHash: String, nowMs: Long): DeviceRow {
        val id = newId()
        conn.prepareStatement("INSERT INTO devices(id,name,kind,token_hash,created_at) VALUES(?,?,?,?,?)").use {
            it.setString(1, id); it.setString(2, name); it.setString(3, kind.name)
            it.setString(4, tokenHash); it.setLong(5, nowMs)
            it.executeUpdate()
        }
        return DeviceRow(id, name, kind, null)
    }

    @Synchronized
    fun deviceByTokenHash(hash: String): DeviceRow? =
        conn.prepareStatement("SELECT * FROM devices WHERE token_hash=?").use {
            it.setString(1, hash)
            it.executeQuery().use { rs -> if (rs.next()) rs.toDevice() else null }
        }

    @Synchronized
    fun device(id: String): DeviceRow? =
        conn.prepareStatement("SELECT * FROM devices WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.toDevice() else null }
        }

    @Synchronized
    fun rename(id: String, name: String) {
        conn.prepareStatement("UPDATE devices SET name=? WHERE id=?").use {
            it.setString(1, name); it.setString(2, id); it.executeUpdate()
        }
    }

    @Synchronized
    fun createPairing(ownerId: String, code: String, nowMs: Long): PairingRow {
        conn.prepareStatement("DELETE FROM pairings WHERE owner_id=? AND state IN ('PENDING','CLAIMED')").use {
            it.setString(1, ownerId); it.executeUpdate()
        }
        val row = PairingRow(newId(), code, ownerId, null, PairingState.PENDING, nowMs + Limits.PAIRING_TTL_MS)
        conn.prepareStatement("INSERT INTO pairings(id,code,owner_id,claimer_id,state,expires_at) VALUES(?,?,?,NULL,?,?)").use {
            it.setString(1, row.id); it.setString(2, row.code); it.setString(3, ownerId)
            it.setString(4, row.state.name); it.setLong(5, row.expiresAtMs)
            it.executeUpdate()
        }
        return row
    }

    /** The unexpired PENDING pairing holding [code], if any. */
    @Synchronized
    fun openPairingByCode(code: String, nowMs: Long): PairingRow? =
        conn.prepareStatement("SELECT * FROM pairings WHERE code=? AND state='PENDING' AND expires_at>=?").use {
            it.setString(1, code); it.setLong(2, nowMs)
            it.executeQuery().use { rs -> if (rs.next()) rs.toPairing() else null }
        }

    /** Atomically moves the open pairing for [code] to CLAIMED; null when none is open. */
    @Synchronized
    fun claimPairing(code: String, claimerId: String, nowMs: Long): PairingRow? {
        val open = openPairingByCode(code, nowMs) ?: return null
        conn.prepareStatement("UPDATE pairings SET state='CLAIMED', claimer_id=? WHERE id=? AND state='PENDING'").use {
            it.setString(1, claimerId); it.setString(2, open.id)
            if (it.executeUpdate() == 0) return null
        }
        return open.copy(claimerId = claimerId, state = PairingState.CLAIMED)
    }

    @Synchronized
    fun pairing(id: String): PairingRow? =
        conn.prepareStatement("SELECT * FROM pairings WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.toPairing() else null }
        }

    @Synchronized
    fun setPairingState(id: String, state: PairingState) {
        conn.prepareStatement("UPDATE pairings SET state=? WHERE id=?").use {
            it.setString(1, state.name); it.setString(2, id); it.executeUpdate()
        }
    }

    @Synchronized
    fun link(a: String, b: String) {
        val (x, y) = if (a < b) a to b else b to a
        conn.prepareStatement("INSERT OR IGNORE INTO links(a,b) VALUES(?,?)").use {
            it.setString(1, x); it.setString(2, y); it.executeUpdate()
        }
    }

    @Synchronized
    fun areLinked(a: String, b: String): Boolean {
        val (x, y) = if (a < b) a to b else b to a
        return conn.prepareStatement("SELECT 1 FROM links WHERE a=? AND b=?").use {
            it.setString(1, x); it.setString(2, y)
            it.executeQuery().use { rs -> rs.next() }
        }
    }

    @Synchronized
    fun peersOf(deviceId: String): List<DeviceRow> =
        conn.prepareStatement(
            """SELECT d.* FROM devices d JOIN links l
               ON (l.a=? AND d.id=l.b) OR (l.b=? AND d.id=l.a) ORDER BY d.created_at, d.id"""
        ).use {
            it.setString(1, deviceId); it.setString(2, deviceId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDevice()) } }
        }

    @Synchronized
    fun unlink(a: String, b: String) {
        val (x, y) = if (a < b) a to b else b to a
        conn.prepareStatement("DELETE FROM links WHERE a=? AND b=?").use {
            it.setString(1, x); it.setString(2, y); it.executeUpdate()
        }
    }

    enum class Resolved { NOT_FOUND, FORBIDDEN, WON, ALREADY_ACCEPTED }

    /** One atomic CLAIMED -> ACCEPTED/REJECTED transition by the owner; links on accept. */
    @Synchronized
    fun resolvePairing(id: String, deviceId: String, accept: Boolean, nowMs: Long): Resolved {
        val row = pairing(id)
        if (row == null || (row.ownerId != deviceId && row.claimerId != deviceId)) return Resolved.NOT_FOUND
        if (row.ownerId != deviceId) return Resolved.FORBIDDEN
        val claimerId = row.claimerId
        if (accept && row.state == PairingState.ACCEPTED && claimerId != null && areLinked(row.ownerId, claimerId)) {
            return Resolved.ALREADY_ACCEPTED
        }
        if (row.state != PairingState.CLAIMED || nowMs > row.expiresAtMs || claimerId == null) return Resolved.NOT_FOUND
        if (accept) link(row.ownerId, claimerId)
        setPairingState(id, if (accept) PairingState.ACCEPTED else PairingState.REJECTED)
        return Resolved.WON
    }
}
