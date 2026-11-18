package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
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
)

data class DeliveryRow(
    val id: String,
    val fromId: String,
    val toId: String,
    val tabs: List<Tab>,
    val sentAtMs: Long,
    val files: List<FileRef> = emptyList(),
)

data class UploadRow(
    val id: String,
    val ownerId: String,
    val relativePath: String,
    val mime: String,
    val size: Long,
    val offset: Long,
    val createdAtMs: Long,
)

enum class AttachResult { OK, ALREADY_SENT, INVALID }

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
                    sent_to TEXT NULL)"""
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS files_delivery ON files(delivery_id)")
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

    private val tabsSerializer = ListSerializer(Tab.serializer())

    @Synchronized
    fun createDelivery(fromId: String, toId: String, tabs: List<Tab>, nowMs: Long, id: String = newId()): String {
        conn.prepareStatement("INSERT INTO deliveries(id,from_id,to_id,tabs_json,sent_at) VALUES(?,?,?,?,?)").use {
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

    /** Idempotent; only the recipient's own delivery is removed. Returns the ids of its file blobs to delete. */
    @Synchronized
    fun deleteDelivery(toId: String, id: String): List<String> = transaction {
        val owned = conn.prepareStatement("SELECT 1 FROM deliveries WHERE id=? AND to_id=?").use {
            it.setString(1, id); it.setString(2, toId)
            it.executeQuery().use { rs -> rs.next() }
        }
        if (!owned) return@transaction emptyList()
        val fileIds = filesOfDelivery(id).map { it.id }
        conn.prepareStatement("DELETE FROM files WHERE delivery_id=?").use { it.setString(1, id); it.executeUpdate() }
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
     * ALREADY_SENT when every id already belongs to a delivery from [ownerId] to [to];
     * INVALID when any id is unknown, someone else's, duplicated, or attached elsewhere.
     */
    @Synchronized
    fun attachFiles(ids: List<String>, ownerId: String, to: String, deliveryId: String): AttachResult = transaction {
        if (ids.isEmpty() || ids.toSet().size != ids.size) return@transaction AttachResult.INVALID
        val rows = ids.map { id ->
            conn.prepareStatement("SELECT owner_id, delivery_id, sent_to FROM files WHERE id=?").use {
                it.setString(1, id)
                it.executeQuery().use { rs ->
                    if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3)) else null
                }
            } ?: return@transaction AttachResult.INVALID
        }
        if (rows.any { it.first != ownerId }) return@transaction AttachResult.INVALID
        if (rows.all { it.second != null && it.third == to }) return@transaction AttachResult.ALREADY_SENT
        if (rows.any { it.second != null }) return@transaction AttachResult.INVALID
        conn.prepareStatement("UPDATE files SET delivery_id=?, sent_to=? WHERE id=?").use {
            for (id in ids) {
                it.setString(1, deliveryId); it.setString(2, to); it.setString(3, id); it.executeUpdate()
            }
        }
        AttachResult.OK
    }

    /** Attaches [fileIds] and creates their delivery in one step; returns the delivery and its files on OK. */
    @Synchronized
    fun createFileDelivery(fromId: String, toId: String, fileIds: List<String>, nowMs: Long): Pair<AttachResult, DeliveryRow?> =
        transaction {
            val id = newId()
            val result = attachFiles(fileIds, fromId, toId, id)
            if (result != AttachResult.OK) return@transaction result to null
            createDelivery(fromId, toId, emptyList(), nowMs, id)
            result to DeliveryRow(id, fromId, toId, emptyList(), nowMs, filesOfDelivery(id))
        }

    /** A file [deviceId] may download: its own, or one sent to it in a live delivery. */
    @Synchronized
    fun fileForDownload(id: String, deviceId: String): FileRef? =
        conn.prepareStatement(
            """SELECT f.* FROM files f LEFT JOIN deliveries d ON d.id=f.delivery_id
               WHERE f.id=? AND (f.owner_id=? OR (f.sent_to=? AND d.to_id=?))"""
        ).use {
            it.setString(1, id); it.setString(2, deviceId); it.setString(3, deviceId); it.setString(4, deviceId)
            it.executeQuery().use { rs -> if (rs.next()) rs.toFileRef() else null }
        }

    /**
     * Deletes stale uploads, unsent files and expired deliveries; returns the blob ids to delete.
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
            addAll(ids("SELECT id FROM files WHERE delivery_id IS NULL AND created_at<=?", unsentCutoff))
            addAll(ids("SELECT f.id FROM files f JOIN deliveries d ON d.id=f.delivery_id WHERE d.sent_at<=?", deliveryCutoff))
        }
        exec("DELETE FROM uploads WHERE created_at<=?", uploadCutoff)
        exec("DELETE FROM files WHERE delivery_id IS NULL AND created_at<=?", unsentCutoff)
        exec("DELETE FROM files WHERE delivery_id IN (SELECT id FROM deliveries WHERE sent_at<=?)", deliveryCutoff)
        exec("DELETE FROM deliveries WHERE sent_at<=?", deliveryCutoff)
        out
    }

    /** Row count of one of the relay's tables; for tests and diagnostics. */
    @Synchronized
    fun rowCount(table: String): Int {
        require(table in setOf("devices", "pairings", "links", "deliveries", "uploads", "files")) { "unknown table" }
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
