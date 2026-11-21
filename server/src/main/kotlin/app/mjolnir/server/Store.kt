package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.MjolnirJson
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Tab
import kotlinx.serialization.builtins.ListSerializer
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
    val kind: CodeKind = CodeKind.PAIRING,
    val spaceId: String? = null,
)

data class DeliveryRow(
    val id: String,
    val fromId: String,
    val toId: String,
    val tabs: List<Tab>,
    val sentAtMs: Long,
    val files: List<FileRef> = emptyList(),
    val spaceId: String? = null,
    val spaceName: String? = null,
)

data class SpaceRow(val id: String, val name: String, val adminId: String, val createdAtMs: Long)

data class UploadRow(
    val id: String,
    val ownerId: String,
    val relativePath: String,
    val mime: String,
    val size: Long,
    val offset: Long,
    val createdAtMs: Long,
)

enum class AttachResult { OK, ALREADY_SENT, INVALID, NOT_ALLOWED }

/** A guest room: open until [expiresAtMs] (or until ended, which deletes the row); [guestId] once redeemed. */
data class RoomRow(val id: String, val ownerId: String, val guestId: String?, val createdAtMs: Long, val expiresAtMs: Long)

/** What ending a room removed: the blobs in [fileIdsToDelete] are for the caller to delete from storage. */
data class EndedRoom(val ownerId: String, val guestId: String?, val fileIdsToDelete: List<String>)

fun DeviceRow.toPeer(): Peer = Peer(id, name, kind)

/** Unaccepted pairing and invite rows are kept this long past their expiry, then purged. */
private const val STALE_PAIRING_MS = 86_400_000L

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
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS deliveries(
                    id TEXT PRIMARY KEY,
                    from_id TEXT NOT NULL,
                    to_id TEXT NOT NULL,
                    tabs_json TEXT NOT NULL,
                    sent_at INTEGER NOT NULL)"""
            )
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS uploads(
                    id TEXT PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    mime TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    offset INTEGER NOT NULL,
                    created_at INTEGER NOT NULL)"""
            )
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS files(
                    id TEXT PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    mime TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    delivery_id TEXT NULL,
                    sent_to TEXT NULL,
                    acked_at INTEGER NULL)"""
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS files_delivery ON files(delivery_id)")
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS spaces(
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    admin_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL)"""
            )
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS space_members(
                    space_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    joined_at INTEGER NOT NULL,
                    PRIMARY KEY(space_id, device_id))"""
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS space_members_device ON space_members(device_id)")
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS rooms(
                    id TEXT PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    secret_hash TEXT NOT NULL UNIQUE,
                    guest_id TEXT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL)"""
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS rooms_owner ON rooms(owner_id)")
            it.executeUpdate("CREATE INDEX IF NOT EXISTS rooms_guest ON rooms(guest_id)")
        }
        addColumnIfMissing("devices", "last_seen", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("pairings", "kind", "TEXT NOT NULL DEFAULT 'PAIRING'")
        addColumnIfMissing("pairings", "space_id", "TEXT NULL")
        addColumnIfMissing("deliveries", "space_id", "TEXT NULL")
        addColumnIfMissing("deliveries", "space_name", "TEXT NULL")
        addColumnIfMissing("files", "downloaded_at", "INTEGER NULL")
        // Databases created before tombstones existed lack acked_at.
        val hasAckedAt = conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(files)").use { rs ->
                var found = false
                while (rs.next()) if (rs.getString("name") == "acked_at") found = true
                found
            }
        }
        if (!hasAckedAt) conn.createStatement().use { it.executeUpdate("ALTER TABLE files ADD COLUMN acked_at INTEGER NULL") }
    }

    /** Additive upgrade for relays whose database predates [column]. */
    private fun addColumnIfMissing(table: String, column: String, definition: String) {
        val present = conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                var found = false
                while (rs.next()) if (rs.getString("name") == column) found = true
                found
            }
        }
        if (!present) conn.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition") }
    }

    private fun ResultSet.toPairing() = PairingRow(
        getString("id"), getString("code"), getString("owner_id"), getString("claimer_id"),
        PairingState.valueOf(getString("state")), getLong("expires_at"),
        CodeKind.valueOf(getString("kind")), getString("space_id"),
    )

    private fun ResultSet.toSpace() = SpaceRow(getString("id"), getString("name"), getString("admin_id"), getLong("created_at"))

    private fun ResultSet.toDevice() =
        DeviceRow(getString("id"), getString("name"), DeviceKind.valueOf(getString("kind")), getString("push_token"))

    @Synchronized
    fun createDevice(name: String, kind: DeviceKind, tokenHash: String, nowMs: Long, id: String = newId()): DeviceRow {
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
    fun setPushToken(deviceId: String, token: String) {
        conn.prepareStatement("UPDATE devices SET push_token=? WHERE id=?").use {
            it.setString(1, token); it.setString(2, deviceId); it.executeUpdate()
        }
    }

    @Synchronized
    fun rename(id: String, name: String) {
        conn.prepareStatement("UPDATE devices SET name=? WHERE id=?").use {
            it.setString(1, name); it.setString(2, id); it.executeUpdate()
        }
    }

    /** Replaces only the owner's previous PENDING code of the same [kind] and [spaceId]. */
    @Synchronized
    fun createPairing(
        ownerId: String, code: String, nowMs: Long,
        kind: CodeKind = CodeKind.PAIRING, spaceId: String? = null,
    ): PairingRow {
        conn.prepareStatement(
            "DELETE FROM pairings WHERE owner_id=? AND state='PENDING' AND kind=? AND space_id IS ?"
        ).use {
            it.setString(1, ownerId); it.setString(2, kind.name); it.setString(3, spaceId); it.executeUpdate()
        }
        val row = PairingRow(newId(), code, ownerId, null, PairingState.PENDING, nowMs + Limits.PAIRING_TTL_MS, kind, spaceId)
        conn.prepareStatement(
            "INSERT INTO pairings(id,code,owner_id,claimer_id,state,expires_at,kind,space_id) VALUES(?,?,?,NULL,?,?,?,?)"
        ).use {
            it.setString(1, row.id); it.setString(2, row.code); it.setString(3, ownerId)
            it.setString(4, row.state.name); it.setLong(5, row.expiresAtMs)
            it.setString(6, kind.name); it.setString(7, spaceId)
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

    enum class Resolved { NOT_FOUND, FORBIDDEN, WON, ALREADY_ACCEPTED, FULL, SPACE_LIMIT, GUEST }

    /**
     * One atomic CLAIMED -> ACCEPTED/REJECTED transition by the owner. Accepting a PAIRING links the two devices;
     * accepting a SPACE_INVITE adds the claimer to the space, provided the owner still administers it and it has
     * room (FULL, with the pairing left CLAIMED).
     */
    @Synchronized
    fun resolvePairing(id: String, deviceId: String, accept: Boolean, nowMs: Long,
        maxMembers: Int = Int.MAX_VALUE, maxSpaces: Int = Int.MAX_VALUE,
    ): Resolved = transaction {
        val row = pairing(id)
        if (row == null || (row.ownerId != deviceId && row.claimerId != deviceId)) return@transaction Resolved.NOT_FOUND
        if (row.ownerId != deviceId) return@transaction Resolved.FORBIDDEN
        val claimerId = row.claimerId
        val invite = row.kind == CodeKind.SPACE_INVITE
        if (accept && row.state == PairingState.ACCEPTED && claimerId != null &&
            (if (invite) row.spaceId?.let { shareSpace(row.ownerId, claimerId, it) } == true else areLinked(row.ownerId, claimerId))
        ) {
            return@transaction Resolved.ALREADY_ACCEPTED
        }
        if (row.state != PairingState.CLAIMED || nowMs > row.expiresAtMs || claimerId == null) return@transaction Resolved.NOT_FOUND
        // A guest is linked only to its room's owner, at join: it never pairs or joins a space.
        if (accept && device(claimerId)?.kind == DeviceKind.GUEST) return@transaction Resolved.GUEST
        if (invite) {
            val space = row.spaceId?.let { space(it) }
            if (space == null || space.adminId != row.ownerId) return@transaction Resolved.NOT_FOUND
            // A vanished device can neither be added nor approve anything.
            if (device(claimerId) == null || device(row.ownerId) == null) return@transaction Resolved.NOT_FOUND
            if (accept) {
                if (spacesOf(claimerId).size >= maxSpaces) return@transaction Resolved.SPACE_LIMIT
                if (members(space.id).size >= maxMembers) return@transaction Resolved.FULL
                addMember(space.id, claimerId, nowMs)
            }
        } else if (accept) {
            link(row.ownerId, claimerId)
        }
        setPairingState(id, if (accept) PairingState.ACCEPTED else PairingState.REJECTED)
        Resolved.WON
    }

    @Synchronized
    fun createSpace(adminId: String, name: String, nowMs: Long): SpaceRow = transaction {
        val row = SpaceRow(newId(), name, adminId, nowMs)
        conn.prepareStatement("INSERT INTO spaces(id,name,admin_id,created_at) VALUES(?,?,?,?)").use {
            it.setString(1, row.id); it.setString(2, name); it.setString(3, adminId); it.setLong(4, nowMs)
            it.executeUpdate()
        }
        addMember(row.id, adminId, nowMs)
        row
    }

    @Synchronized
    fun space(id: String): SpaceRow? =
        conn.prepareStatement("SELECT * FROM spaces WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.toSpace() else null }
        }

    @Synchronized
    fun spacesOf(deviceId: String): List<SpaceRow> =
        conn.prepareStatement(
            """SELECT s.* FROM spaces s JOIN space_members m ON m.space_id=s.id
               WHERE m.device_id=? ORDER BY s.created_at, s.id"""
        ).use {
            it.setString(1, deviceId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSpace()) } }
        }

    /** Members of [spaceId], earliest joiner first. */
    @Synchronized
    fun members(spaceId: String): List<DeviceRow> =
        conn.prepareStatement(
            """SELECT d.* FROM devices d JOIN space_members m ON m.device_id=d.id
               WHERE m.space_id=? ORDER BY m.joined_at, d.id"""
        ).use {
            it.setString(1, spaceId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDevice()) } }
        }

    @Synchronized
    fun addMember(spaceId: String, deviceId: String, nowMs: Long) {
        conn.prepareStatement("INSERT OR IGNORE INTO space_members(space_id,device_id,joined_at) VALUES(?,?,?)").use {
            it.setString(1, spaceId); it.setString(2, deviceId); it.setLong(3, nowMs); it.executeUpdate()
        }
    }

    enum class Removed { REMOVED, NOT_MEMBER, NOT_ADMIN, ADMIN_MUST_TRANSFER }

    /**
     * Removes [deviceId] on behalf of [actingId]: a device may remove itself, the admin may remove others.
     * The admin can never be removed, so a space always keeps an admin who is a member. Checked and written
     * under one lock, so a transfer racing a leave cannot orphan the admin seat.
     */
    @Synchronized
    fun removeMember(spaceId: String, deviceId: String, actingId: String): Removed {
        val space = space(spaceId) ?: return Removed.NOT_MEMBER
        if (deviceId == space.adminId) return Removed.ADMIN_MUST_TRANSFER
        if (actingId != deviceId && actingId != space.adminId) return Removed.NOT_ADMIN
        val removed = conn.prepareStatement("DELETE FROM space_members WHERE space_id=? AND device_id=?").use {
            it.setString(1, spaceId); it.setString(2, deviceId); it.executeUpdate() > 0
        }
        return if (removed) Removed.REMOVED else Removed.NOT_MEMBER
    }

    /** Renames only when [actingId] is the current admin; false otherwise. */
    @Synchronized
    fun renameSpace(id: String, actingId: String, name: String): Boolean =
        conn.prepareStatement("UPDATE spaces SET name=? WHERE id=? AND admin_id=?").use {
            it.setString(1, name); it.setString(2, id); it.setString(3, actingId); it.executeUpdate() > 0
        }

    /** Deletes the space, its memberships and its invite codes when [actingId] is the admin; returns the member ids to notify, or null. */
    @Synchronized
    fun deleteSpace(id: String, actingId: String): List<String>? = transaction {
        if (space(id)?.adminId != actingId) return@transaction null
        val ids = members(id).map { it.id }
        conn.prepareStatement("DELETE FROM space_members WHERE space_id=?").use { it.setString(1, id); it.executeUpdate() }
        conn.prepareStatement("DELETE FROM pairings WHERE space_id=?").use { it.setString(1, id); it.executeUpdate() }
        conn.prepareStatement("DELETE FROM spaces WHERE id=?").use { it.setString(1, id); it.executeUpdate() }
        ids
    }

    /** One UPDATE guarded by the current admin and the target's membership, so of two racing transfers exactly one wins. */
    @Synchronized
    fun transferAdmin(spaceId: String, fromId: String, toId: String): Boolean =
        conn.prepareStatement(
            """UPDATE spaces SET admin_id=? WHERE id=? AND admin_id=?
               AND EXISTS(SELECT 1 FROM space_members WHERE space_id=? AND device_id=?)"""
        ).use {
            it.setString(1, toId); it.setString(2, spaceId); it.setString(3, fromId)
            it.setString(4, spaceId); it.setString(5, toId)
            it.executeUpdate() > 0
        }

    /** Removes a device row; its pairings and memberships are left to the purge and the guards above. */
    internal fun deleteDevice(id: String) {
        synchronized(this) {
            conn.prepareStatement("DELETE FROM devices WHERE id=?").use { it.setString(1, id); it.executeUpdate() }
        }
    }

    @Synchronized
    fun shareSpace(a: String, b: String, spaceId: String): Boolean =
        conn.prepareStatement(
            "SELECT COUNT(*) FROM space_members WHERE space_id=? AND device_id IN (?,?)"
        ).use {
            it.setString(1, spaceId); it.setString(2, a); it.setString(3, b)
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) == if (a == b) 1 else 2 }
        }

    /** Writes only when more than 60 s have passed since the last write. */
    @Synchronized
    fun touchLastSeen(deviceId: String, nowMs: Long) {
        conn.prepareStatement("UPDATE devices SET last_seen=? WHERE id=? AND ?-last_seen>60000").use {
            it.setLong(1, nowMs); it.setString(2, deviceId); it.setLong(3, nowMs); it.executeUpdate()
        }
    }

    @Synchronized
    fun lastSeen(deviceId: String): Long =
        conn.prepareStatement("SELECT last_seen FROM devices WHERE id=?").use {
            it.setString(1, deviceId)
            it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    private val tabsSerializer = ListSerializer(Tab.serializer())

    @Synchronized
    fun createDelivery(
        fromId: String, toId: String, tabs: List<Tab>, nowMs: Long, id: String = newId(),
        spaceId: String? = null, spaceName: String? = null,
    ): String {
        conn.prepareStatement(
            "INSERT INTO deliveries(id,from_id,to_id,tabs_json,sent_at,space_id,space_name) VALUES(?,?,?,?,?,?,?)"
        ).use {
            it.setString(6, spaceId); it.setString(7, spaceName)
            it.setString(1, id); it.setString(2, fromId); it.setString(3, toId)
            it.setString(4, MjolnirJson.encodeToString(tabsSerializer, tabs)); it.setLong(5, nowMs)
            it.executeUpdate()
        }
        return id
    }

    /** Unexpired deliveries for [toId], oldest first, with their files. */
    @Synchronized
    fun inbox(toId: String, nowMs: Long): List<DeliveryRow> {
        val rows = conn.prepareStatement("SELECT * FROM deliveries WHERE to_id=? AND sent_at>? ORDER BY sent_at, rowid").use {
            it.setString(1, toId); it.setLong(2, nowMs - Limits.DELIVERY_TTL_MS)
            it.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        DeliveryRow(
                            rs.getString("id"), rs.getString("from_id"), rs.getString("to_id"),
                            MjolnirJson.decodeFromString(tabsSerializer, rs.getString("tabs_json")), rs.getLong("sent_at"),
                            spaceId = rs.getString("space_id"), spaceName = rs.getString("space_name"),
                        )
                    )
                }
            }
        }
        return rows.map { it.copy(files = filesOfDelivery(it.id)) }
    }

    private fun ResultSet.toFileRef() = FileRef(
        getString("id"), getString("name"), getLong("size"), getString("mime"), getString("sha256"), getString("relative_path"),
    )

    private fun filesOfDelivery(deliveryId: String): List<FileRef> =
        conn.prepareStatement("SELECT * FROM files WHERE delivery_id=? ORDER BY rowid").use {
            it.setString(1, deliveryId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toFileRef()) } }
        }

    /**
     * Idempotent; only the recipient's own delivery is removed. Returns the ids of its file blobs to delete.
     * The file rows stay as tombstones (acked_at set, delivery_id cleared, sent_to kept) for [Limits.DELIVERY_TTL_MS],
     * so a sender retrying after a lost send response gets ALREADY_SENT instead of INVALID.
     */
    @Synchronized
    fun deleteDelivery(toId: String, id: String, nowMs: Long): List<String> = transaction {
        val owned = conn.prepareStatement("SELECT 1 FROM deliveries WHERE id=? AND to_id=?").use {
            it.setString(1, id); it.setString(2, toId)
            it.executeQuery().use { rs -> rs.next() }
        }
        if (!owned) return@transaction emptyList()
        val fileIds = filesOfDelivery(id).map { it.id }
        conn.prepareStatement("UPDATE files SET acked_at=?, delivery_id=NULL WHERE delivery_id=?").use {
            it.setLong(1, nowMs); it.setString(2, id); it.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM deliveries WHERE id=?").use { it.setString(1, id); it.executeUpdate() }
        fileIds
    }

    @Synchronized
    fun createUpload(id: String, ownerId: String, relativePath: String, mime: String, size: Long, nowMs: Long): UploadRow {
        val row = UploadRow(id, ownerId, relativePath, mime, size, 0, nowMs)
        conn.prepareStatement(
            "INSERT INTO uploads(id,owner_id,relative_path,mime,size,offset,created_at) VALUES(?,?,?,?,?,0,?)"
        ).use {
            it.setString(1, id); it.setString(2, ownerId); it.setString(3, relativePath)
            it.setString(4, mime); it.setLong(5, size); it.setLong(6, nowMs)
            it.executeUpdate()
        }
        return row
    }

    /** The open upload [id] if [ownerId] owns it. */
    @Synchronized
    fun upload(id: String, ownerId: String): UploadRow? =
        conn.prepareStatement("SELECT * FROM uploads WHERE id=? AND owner_id=?").use {
            it.setString(1, id); it.setString(2, ownerId)
            it.executeQuery().use { rs ->
                if (!rs.next()) null else UploadRow(
                    rs.getString("id"), rs.getString("owner_id"), rs.getString("relative_path"), rs.getString("mime"),
                    rs.getLong("size"), rs.getLong("offset"), rs.getLong("created_at"),
                )
            }
        }

    @Synchronized
    fun setUploadOffset(id: String, offset: Long) {
        conn.prepareStatement("UPDATE uploads SET offset=? WHERE id=?").use {
            it.setLong(1, offset); it.setString(2, id); it.executeUpdate()
        }
    }

    /** Moves upload [id] into the files table as an unsent file. */
    @Synchronized
    fun completeUpload(id: String, sha: String, nowMs: Long): FileRef = transaction {
        val up = conn.prepareStatement("SELECT * FROM uploads WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rs ->
                check(rs.next()) { "no upload $id" }
                UploadRow(
                    rs.getString("id"), rs.getString("owner_id"), rs.getString("relative_path"), rs.getString("mime"),
                    rs.getLong("size"), rs.getLong("offset"), rs.getLong("created_at"),
                )
            }
        }
        val ref = FileRef(id, up.relativePath.substringAfterLast('/'), up.size, up.mime, sha, up.relativePath)
        conn.prepareStatement(
            "INSERT INTO files(id,owner_id,name,relative_path,mime,size,sha256,created_at) VALUES(?,?,?,?,?,?,?,?)"
        ).use {
            it.setString(1, id); it.setString(2, up.ownerId); it.setString(3, ref.name); it.setString(4, ref.relativePath)
            it.setString(5, ref.mimeType); it.setLong(6, ref.sizeBytes); it.setString(7, sha); it.setLong(8, nowMs)
            it.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM uploads WHERE id=?").use { it.setString(1, id); it.executeUpdate() }
        ref
    }

    /** A complete file [ownerId] uploaded (sent or not). */
    @Synchronized
    fun ownFile(id: String, ownerId: String): FileRef? =
        conn.prepareStatement("SELECT * FROM files WHERE id=? AND owner_id=?").use {
            it.setString(1, id); it.setString(2, ownerId)
            it.executeQuery().use { rs -> if (rs.next()) rs.toFileRef() else null }
        }

    /**
     * Attaches files [ids] owned by [ownerId] to delivery [deliveryId] for [to].
     * ALREADY_SENT when every id was already sent to [to] (still in a delivery, or acked and kept as a tombstone);
     * INVALID when any id is unknown, someone else's, duplicated, or already sent elsewhere.
     */
    @Synchronized
    fun attachFiles(ids: List<String>, ownerId: String, to: String, deliveryId: String): AttachResult = transaction {
        if (ids.isEmpty() || ids.toSet().size != ids.size) return@transaction AttachResult.INVALID
        val rows = ids.map { id ->
            conn.prepareStatement("SELECT owner_id, delivery_id, sent_to, acked_at FROM files WHERE id=?").use {
                it.setString(1, id)
                it.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else {
                        val sent = rs.getString(2) != null || rs.getObject(4) != null
                        Triple(rs.getString(1), sent, rs.getString(3))
                    }
                }
            } ?: return@transaction AttachResult.INVALID
        }
        if (rows.any { it.first != ownerId }) return@transaction AttachResult.INVALID
        if (rows.all { it.second && it.third == to }) return@transaction AttachResult.ALREADY_SENT
        if (rows.any { it.second }) return@transaction AttachResult.INVALID
        conn.prepareStatement("UPDATE files SET delivery_id=?, sent_to=? WHERE id=?").use {
            for (id in ids) {
                it.setString(1, deliveryId); it.setString(2, to); it.setString(3, id); it.executeUpdate()
            }
        }
        AttachResult.OK
    }

    /** Attaches [fileIds] and creates their delivery in one step; returns the delivery and its files on OK. */
    @Synchronized
    fun createFileDelivery(
        fromId: String, toId: String, fileIds: List<String>, nowMs: Long,
        spaceId: String? = null, spaceName: String? = null,
    ): Pair<AttachResult, DeliveryRow?> =
        transaction {
            // The route checked the link (or the shared space) before; re-check it here, under the lock, so a send
            // racing the end of a guest room (or an unpair) never attaches files to a device that is gone.
            val allowed = device(toId) != null &&
                (if (spaceId != null) shareSpace(fromId, toId, spaceId) else areLinked(fromId, toId))
            if (!allowed) return@transaction AttachResult.NOT_ALLOWED to null
            val id = newId()
            val result = attachFiles(fileIds, fromId, toId, id)
            if (result != AttachResult.OK) return@transaction result to null
            createDelivery(fromId, toId, emptyList(), nowMs, id, spaceId, spaceName)
            result to DeliveryRow(id, fromId, toId, emptyList(), nowMs, filesOfDelivery(id), spaceId, spaceName)
        }

    /** A file [deviceId] may download: its own, or one sent to it in a live delivery; never an acked tombstone. */
    @Synchronized
    fun fileForDownload(id: String, deviceId: String): FileRef? =
        conn.prepareStatement(
            """SELECT f.* FROM files f LEFT JOIN deliveries d ON d.id=f.delivery_id
               WHERE f.id=? AND f.acked_at IS NULL AND (f.owner_id=? OR (f.sent_to=? AND d.to_id=?))"""
        ).use {
            it.setString(1, id); it.setString(2, deviceId); it.setString(3, deviceId); it.setString(4, deviceId)
            it.executeQuery().use { rs -> if (rs.next()) rs.toFileRef() else null }
        }

    /**
     * Deletes stale uploads, unsent files, expired deliveries and old ack tombstones; returns the blob ids to delete.
     */
    @Synchronized
    fun purgeExpired(nowMs: Long): List<String> = transaction {
        fun ids(sql: String, cutoff: Long): List<String> = conn.prepareStatement(sql).use {
            it.setLong(1, cutoff)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        fun exec(sql: String, cutoff: Long) = conn.prepareStatement(sql).use { it.setLong(1, cutoff); it.executeUpdate() }

        val uploadCutoff = nowMs - Limits.INCOMPLETE_UPLOAD_TTL_MS
        val unsentCutoff = nowMs - Limits.UNSENT_FILE_TTL_MS
        val deliveryCutoff = nowMs - Limits.DELIVERY_TTL_MS
        val out = buildList {
            addAll(ids("SELECT id FROM uploads WHERE created_at<=?", uploadCutoff))
            addAll(ids("SELECT id FROM files WHERE delivery_id IS NULL AND acked_at IS NULL AND created_at<=?", unsentCutoff))
            addAll(ids("SELECT f.id FROM files f JOIN deliveries d ON d.id=f.delivery_id WHERE d.sent_at<=?", deliveryCutoff))
        }
        exec("DELETE FROM uploads WHERE created_at<=?", uploadCutoff)
        exec("DELETE FROM files WHERE delivery_id IS NULL AND acked_at IS NULL AND created_at<=?", unsentCutoff)
        exec("DELETE FROM files WHERE acked_at IS NOT NULL AND acked_at<=?", deliveryCutoff) // blobs went on ack
        exec("DELETE FROM files WHERE delivery_id IN (SELECT id FROM deliveries WHERE sent_at<=?)", deliveryCutoff)
        exec("DELETE FROM deliveries WHERE sent_at<=?", deliveryCutoff)
        exec("DELETE FROM pairings WHERE state<>'ACCEPTED' AND expires_at<?", nowMs - STALE_PAIRING_MS)
        out
    }

    private fun ResultSet.toRoom() =
        RoomRow(getString("id"), getString("owner_id"), getString("guest_id"), getLong("created_at"), getLong("expires_at"))

    private fun roomWhere(clause: String, vararg args: Any): RoomRow? =
        conn.prepareStatement("SELECT * FROM rooms WHERE $clause").use {
            args.forEachIndexed { i, a -> it.setObject(i + 1, a) }
            it.executeQuery().use { rs -> if (rs.next()) rs.toRoom() else null }
        }

    /**
     * Opens a room for [ownerId], or returns null when it already has an open one (checked under the same lock, so
     * two racing requests never open two). [secretHash] is the SHA-256 of the invite secret, which is never stored.
     */
    @Synchronized
    fun createRoom(ownerId: String, secretHash: String, nowMs: Long, ttlMs: Long): RoomRow? {
        if (openRoomOf(ownerId, nowMs) != null) return null
        val row = RoomRow(newId(), ownerId, null, nowMs, nowMs + ttlMs)
        conn.prepareStatement("INSERT INTO rooms(id,owner_id,secret_hash,guest_id,created_at,expires_at) VALUES(?,?,?,NULL,?,?)").use {
            it.setString(1, row.id); it.setString(2, ownerId); it.setString(3, secretHash)
            it.setLong(4, nowMs); it.setLong(5, row.expiresAtMs)
            it.executeUpdate()
        }
        return row
    }

    /** The owner's unexpired room, if any. */
    @Synchronized
    fun openRoomOf(ownerId: String, nowMs: Long): RoomRow? = roomWhere("owner_id=? AND expires_at>?", ownerId, nowMs)

    /** The room whose invite secret hashes to [hash], expired or not. */
    @Synchronized
    fun roomBySecretHash(hash: String): RoomRow? = roomWhere("secret_hash=?", hash)

    @Synchronized
    fun room(id: String): RoomRow? = roomWhere("id=?", id)

    /** The room [guestId] joined, expired or not; null for a device that is not a guest of a live room row. */
    @Synchronized
    fun roomOfGuest(guestId: String): RoomRow? = roomWhere("guest_id=?", guestId)

    /** Binds [guestId] to the room; false when the room is gone or already redeemed. */
    @Synchronized
    fun redeemRoom(roomId: String, guestId: String): Boolean =
        conn.prepareStatement("UPDATE rooms SET guest_id=? WHERE id=? AND guest_id IS NULL").use {
            it.setString(1, guestId); it.setString(2, roomId); it.executeUpdate() > 0
        }

    /**
     * Redeems room [roomId] for a new guest device holding [tokenHash] and links it to the owner, all at once.
     * [JoinResult.Closed] when the room is gone or expired, [JoinResult.InUse] when it was already redeemed.
     */
    sealed interface JoinResult {
        data class Joined(val guest: DeviceRow) : JoinResult
        data object InUse : JoinResult
        data object Closed : JoinResult
    }

    @Synchronized
    fun joinRoom(roomId: String, tokenHash: String, nowMs: Long): JoinResult = transaction {
        val guestId = newId()
        // Re-checked under the lock: a room that ended or expired since the caller looked it up is closed, not in use.
        val room = room(roomId)?.takeIf { it.expiresAtMs > nowMs } ?: return@transaction JoinResult.Closed
        if (!redeemRoom(roomId, guestId)) return@transaction JoinResult.InUse
        val guest = createDevice("Guest", DeviceKind.GUEST, tokenHash, nowMs, guestId)
        link(room.ownerId, guestId)
        JoinResult.Joined(guest)
    }

    /** Ids of rooms past their expiry that are not yet ended. */
    @Synchronized
    fun expiredRooms(nowMs: Long): List<String> =
        conn.prepareStatement("SELECT id FROM rooms WHERE expires_at<=? ORDER BY expires_at").use {
            it.setLong(1, nowMs)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    /**
     * Ends the room: deletes the room, the guest device, its links, the deliveries and files addressed to the guest,
     * the guest's open uploads and its unsent files. Deliveries the guest sent to the owner are kept. Null when the
     * room was already ended.
     */
    @Synchronized
    fun endRoom(roomId: String): EndedRoom? = transaction {
        val room = room(roomId) ?: return@transaction null
        val guest = room.guestId
        val blobs = ArrayList<String>()
        if (guest != null) {
            fun ids(sql: String): List<String> = conn.prepareStatement(sql).use {
                it.setString(1, guest)
                it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            fun exec(sql: String) = conn.prepareStatement(sql).use { it.setString(1, guest); it.executeUpdate() }
            blobs += ids("SELECT id FROM uploads WHERE owner_id=?")
            blobs += ids("SELECT id FROM files WHERE sent_to=? AND acked_at IS NULL")
            blobs += ids("SELECT id FROM files WHERE owner_id=? AND delivery_id IS NULL AND acked_at IS NULL")
            exec("DELETE FROM uploads WHERE owner_id=?")
            exec("DELETE FROM files WHERE sent_to=?")
            exec("DELETE FROM files WHERE owner_id=? AND delivery_id IS NULL")
            exec("DELETE FROM deliveries WHERE to_id=?")
            exec("DELETE FROM links WHERE a=?1 OR b=?1")
            exec("DELETE FROM pairings WHERE owner_id=?1 OR claimer_id=?1")
            exec("DELETE FROM devices WHERE id=?")
        }
        conn.prepareStatement("DELETE FROM rooms WHERE id=?").use { it.setString(1, roomId); it.executeUpdate() }
        EndedRoom(room.ownerId, guest, blobs)
    }

    /**
     * Bytes a guest room holds: the guest's open uploads and live files, plus live files addressed to the guest.
     */
    @Synchronized
    fun roomBytes(guestId: String): Long =
        conn.prepareStatement(
            """SELECT (SELECT COALESCE(SUM(size),0) FROM uploads WHERE owner_id=?)
                    + (SELECT COALESCE(SUM(size),0) FROM files WHERE acked_at IS NULL AND (owner_id=? OR sent_to=?))"""
        ).use {
            it.setString(1, guestId); it.setString(2, guestId); it.setString(3, guestId)
            it.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

    /** Total size of those of [ids] that [ownerId] uploaded and has not sent yet. */
    @Synchronized
    fun unsentBytes(ownerId: String, ids: List<String>): Long {
        if (ids.isEmpty()) return 0
        val marks = ids.joinToString(",") { "?" }
        return conn.prepareStatement(
            "SELECT COALESCE(SUM(size),0) FROM files WHERE owner_id=? AND delivery_id IS NULL AND acked_at IS NULL AND id IN ($marks)"
        ).use {
            it.setString(1, ownerId)
            ids.forEachIndexed { i, id -> it.setString(i + 2, id) }
            it.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** Records that file [fileId] was served to its recipient in full; true the first time. */
    @Synchronized
    fun markDownloaded(fileId: String, nowMs: Long): Boolean =
        conn.prepareStatement("UPDATE files SET downloaded_at=? WHERE id=? AND downloaded_at IS NULL").use {
            it.setLong(1, nowMs); it.setString(2, fileId); it.executeUpdate() > 0
        }

    @Synchronized
    fun downloadedAt(fileId: String): Long? =
        conn.prepareStatement("SELECT downloaded_at FROM files WHERE id=?").use {
            it.setString(1, fileId)
            it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() } else null }
        }

    /** The live delivery holding file [fileId], if it is addressed to [toId]. */
    @Synchronized
    fun deliveryOfFileFor(fileId: String, toId: String): DeliveryRow? =
        conn.prepareStatement(
            "SELECT d.* FROM files f JOIN deliveries d ON d.id=f.delivery_id WHERE f.id=? AND d.to_id=? AND f.acked_at IS NULL"
        ).use {
            it.setString(1, fileId); it.setString(2, toId)
            it.executeQuery().use { rs -> if (rs.next()) rs.toDeliveryRow() else null }
        }

    /** Delivery [id] with its files, if it is addressed to [toId]. */
    @Synchronized
    fun deliveryFor(id: String, toId: String): DeliveryRow? =
        conn.prepareStatement("SELECT * FROM deliveries WHERE id=? AND to_id=?").use {
            it.setString(1, id); it.setString(2, toId)
            it.executeQuery().use { rs -> if (rs.next()) rs.toDeliveryRow() else null }
        }?.let { it.copy(files = filesOfDelivery(it.id)) }

    private fun ResultSet.toDeliveryRow() = DeliveryRow(
        getString("id"), getString("from_id"), getString("to_id"),
        MjolnirJson.decodeFromString(tabsSerializer, getString("tabs_json")), getLong("sent_at"),
        spaceId = getString("space_id"), spaceName = getString("space_name"),
    )

    /** Row count of one of the relay's tables; for tests and diagnostics. */
    @Synchronized
    fun rowCount(table: String): Int {
        require(table in setOf("devices", "pairings", "links", "deliveries", "uploads", "files", "spaces", "space_members", "rooms")) { "unknown table" }
        return conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) } }
    }

    private inline fun <T> transaction(block: () -> T): T {
        if (!conn.autoCommit) return block()
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }
}
