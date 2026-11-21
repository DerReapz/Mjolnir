package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.InviteState
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.MjolnirJson
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Tab
import kotlinx.serialization.Serializable
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

data class ChatRow(val id: String, val spaceId: String, val name: String, val ownerId: String, val createdAtMs: Long, val rev: Long)

data class ChatInviteRow(
    val id: String,
    val chatId: String,
    val inviteeId: String,
    val inviterId: String,
    val state: InviteState,
    val createdAtMs: Long,
)

/** [text] is null once the message is [deleted]. */
data class ChatMessageRow(
    val id: String,
    val chatId: String,
    val authorId: String,
    val rev: Long,
    val sentAtMs: Long,
    val editedAtMs: Long?,
    val deleted: Boolean,
    val deletedByAdmin: Boolean,
    val text: String?,
    /** Set for system entries (membership and settings changes), which have no text; the author is the actor. */
    val event: ChatEvent? = null,
)

/** Who to notify after a device left a space: chat id to the device ids that should hear about it. */
data class LeftSpaceResult(
    val changed: Map<String, List<String>> = emptyMap(),
    val deleted: Map<String, List<String>> = emptyMap(),
)

/** [result] of removing a space member, plus the chat fallout in [chats] (empty unless REMOVED). */
data class SpaceRemoval(val result: Store.Removed, val chats: LeftSpaceResult = LeftSpaceResult())

/** A deleted space: its [memberIds] and the chats that went with it (chat id to member ids). */
data class DeletedSpace(val memberIds: List<String>, val chats: Map<String, List<String>>)

/** What `chat_messages.body_json` holds; attachments join it in the media plan. */
@Serializable
private data class StoredChatBody(val text: String? = null)

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
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS chats(
                    id TEXT PRIMARY KEY,
                    space_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    owner_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    rev INTEGER NOT NULL DEFAULT 0)"""
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS chats_space ON chats(space_id)")
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS chat_members(
                    chat_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    joined_at INTEGER NOT NULL,
                    read_rev INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(chat_id, device_id))"""
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS chat_members_device ON chat_members(device_id)")
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS chat_invites(
                    id TEXT PRIMARY KEY,
                    chat_id TEXT NOT NULL,
                    invitee_id TEXT NOT NULL,
                    inviter_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL)"""
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS chat_invites_chat ON chat_invites(chat_id)")
            it.executeUpdate("CREATE INDEX IF NOT EXISTS chat_invites_invitee ON chat_invites(invitee_id)")
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS chat_messages(
                    id TEXT PRIMARY KEY,
                    chat_id TEXT NOT NULL,
                    author_id TEXT NOT NULL,
                    rev INTEGER NOT NULL,
                    sent_at INTEGER NOT NULL,
                    edited_at INTEGER NULL,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    deleted_by_admin INTEGER NOT NULL DEFAULT 0,
                    body_json TEXT NOT NULL,
                    event_json TEXT NULL)"""
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS chat_messages_chat_rev ON chat_messages(chat_id, rev)")
        }
        addColumnIfMissing("devices", "last_seen", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("pairings", "kind", "TEXT NOT NULL DEFAULT 'PAIRING'")
        addColumnIfMissing("pairings", "space_id", "TEXT NULL")
        addColumnIfMissing("deliveries", "space_id", "TEXT NULL")
        addColumnIfMissing("deliveries", "space_name", "TEXT NULL")
        addColumnIfMissing("files", "downloaded_at", "INTEGER NULL")
        // Relays that already hold the chat tables from an earlier build of this feature.
        addColumnIfMissing("chat_messages", "deleted_by_admin", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("chat_messages", "event_json", "TEXT NULL")
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
    fun removeMember(spaceId: String, deviceId: String, actingId: String, nowMs: Long): SpaceRemoval = transaction {
        val space = space(spaceId) ?: return@transaction SpaceRemoval(Removed.NOT_MEMBER)
        if (deviceId == space.adminId) return@transaction SpaceRemoval(Removed.ADMIN_MUST_TRANSFER)
        if (actingId != deviceId && actingId != space.adminId) return@transaction SpaceRemoval(Removed.NOT_ADMIN)
        val removed = conn.prepareStatement("DELETE FROM space_members WHERE space_id=? AND device_id=?").use {
            it.setString(1, spaceId); it.setString(2, deviceId); it.executeUpdate() > 0
        }
        if (removed) SpaceRemoval(Removed.REMOVED, onLeftSpace(spaceId, deviceId, nowMs, actingId)) else SpaceRemoval(Removed.NOT_MEMBER)
    }

    /** Renames only when [actingId] is the current admin; false otherwise. */
    @Synchronized
    fun renameSpace(id: String, actingId: String, name: String): Boolean =
        conn.prepareStatement("UPDATE spaces SET name=? WHERE id=? AND admin_id=?").use {
            it.setString(1, name); it.setString(2, id); it.setString(3, actingId); it.executeUpdate() > 0
        }

    /** Deletes the space, its memberships, chats and invite codes when [actingId] is the admin; returns who to notify, or null. */
    @Synchronized
    fun deleteSpace(id: String, actingId: String): DeletedSpace? = transaction {
        if (space(id)?.adminId != actingId) return@transaction null
        val ids = members(id).map { it.id }
        val chats = chatsIn(id).associate { it.id to deleteChat(it.id) }
        conn.prepareStatement("DELETE FROM space_members WHERE space_id=?").use { it.setString(1, id); it.executeUpdate() }
        conn.prepareStatement("DELETE FROM pairings WHERE space_id=?").use { it.setString(1, id); it.executeUpdate() }
        conn.prepareStatement("DELETE FROM spaces WHERE id=?").use { it.setString(1, id); it.executeUpdate() }
        DeletedSpace(ids, chats)
    }

    // ---- Chats ----

    private fun ResultSet.toChat() = ChatRow(getString("id"), getString("space_id"), getString("name"), getString("owner_id"), getLong("created_at"), getLong("rev"))

    private fun ResultSet.toInvite() = ChatInviteRow(
        getString("id"), getString("chat_id"), getString("invitee_id"), getString("inviter_id"),
        InviteState.valueOf(getString("state")), getLong("created_at"),
    )

    private fun ResultSet.toChatMessage(): ChatMessageRow {
        val deleted = getInt("deleted") != 0
        val text = if (deleted) null else MjolnirJson.decodeFromString<StoredChatBody>(getString("body_json")).text
        val edited = getLong("edited_at").takeIf { !wasNull() }
        return ChatMessageRow(
            getString("id"), getString("chat_id"), getString("author_id"), getLong("rev"), getLong("sent_at"),
            edited, deleted, getInt("deleted_by_admin") != 0, text,
            getString("event_json")?.let { MjolnirJson.decodeFromString(ChatEvent.serializer(), it) },
        )
    }

    private fun isSpaceMember(spaceId: String, deviceId: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM space_members WHERE space_id=? AND device_id=?").use {
            it.setString(1, spaceId); it.setString(2, deviceId); it.executeQuery().use { rs -> rs.next() }
        }

    private fun chatsIn(spaceId: String): List<ChatRow> =
        conn.prepareStatement("SELECT * FROM chats WHERE space_id=? ORDER BY created_at, id").use {
            it.setString(1, spaceId); it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toChat()) } }
        }

    private fun update(sql: String, vararg args: Any?): Int = conn.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> when (a) { is String -> st.setString(i + 1, a); is Long -> st.setLong(i + 1, a); is Int -> st.setInt(i + 1, a); null -> st.setNull(i + 1, java.sql.Types.NULL); else -> error("arg") } }
        st.executeUpdate()
    }

    /** Creates a chat in [spaceId] with [ownerId] as its first member; null unless [ownerId] is in that space. */
    @Synchronized
    fun createChat(spaceId: String, ownerId: String, name: String, nowMs: Long): ChatRow? = transaction {
        if (!isSpaceMember(spaceId, ownerId)) return@transaction null
        val row = ChatRow(newId(), spaceId, name, ownerId, nowMs, 0)
        update("INSERT INTO chats(id,space_id,name,owner_id,created_at,rev) VALUES(?,?,?,?,?,0)", row.id, spaceId, name, ownerId, nowMs)
        update("INSERT INTO chat_members(chat_id,device_id,joined_at) VALUES(?,?,?)", row.id, ownerId, nowMs)
        row
    }

    @Synchronized
    fun chat(id: String): ChatRow? =
        conn.prepareStatement("SELECT * FROM chats WHERE id=?").use {
            it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) rs.toChat() else null }
        }

    /** Chats of [spaceId] that [deviceId] may see: its own, or every one when [isAdmin]. */
    @Synchronized
    fun chatsOf(spaceId: String, deviceId: String, isAdmin: Boolean): List<ChatRow> =
        if (isAdmin) chatsIn(spaceId)
        else conn.prepareStatement(
            """SELECT c.* FROM chats c JOIN chat_members m ON m.chat_id=c.id
               WHERE c.space_id=? AND m.device_id=? ORDER BY c.created_at, c.id"""
        ).use {
            it.setString(1, spaceId); it.setString(2, deviceId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toChat()) } }
        }

    @Synchronized
    fun isChatMember(chatId: String, deviceId: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM chat_members WHERE chat_id=? AND device_id=?").use {
            it.setString(1, chatId); it.setString(2, deviceId); it.executeQuery().use { rs -> rs.next() }
        }

    /** Members of [chatId], earliest joiner first. */
    @Synchronized
    fun chatMembers(chatId: String): List<DeviceRow> =
        conn.prepareStatement(
            """SELECT d.* FROM devices d JOIN chat_members m ON m.device_id=d.id
               WHERE m.chat_id=? ORDER BY m.joined_at, d.id"""
        ).use {
            it.setString(1, chatId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDevice()) } }
        }

    @Synchronized
    fun renameChat(id: String, name: String, actorId: String, nowMs: Long): Boolean = transaction {
        if (update("UPDATE chats SET name=? WHERE id=?", name, id) == 0) return@transaction false
        recordEvent(id, actorId, ChatEvent.Renamed(name), nowMs)
        true
    }

    /** Deletes the chat with its messages, members and invites; returns the member ids that were in it (to notify). */
    @Synchronized
    fun deleteChat(id: String): List<String> = transaction {
        val ids = chatMembers(id).map { it.id }
        update("DELETE FROM chat_messages WHERE chat_id=?", id)
        update("DELETE FROM chat_members WHERE chat_id=?", id)
        update("DELETE FROM chat_invites WHERE chat_id=?", id)
        update("DELETE FROM chats WHERE id=?", id)
        ids
    }

    /** Adds [deviceId] to [chatId] when it is a member of the chat's space (so never a guest); false otherwise or if already in. */
    @Synchronized
    fun addChatMember(chatId: String, deviceId: String, nowMs: Long): Boolean = transaction {
        val chat = chat(chatId) ?: return@transaction false
        if (!isSpaceMember(chat.spaceId, deviceId)) return@transaction false
        if (update("INSERT OR IGNORE INTO chat_members(chat_id,device_id,joined_at) VALUES(?,?,?)", chatId, deviceId, nowMs) == 0) return@transaction false
        recordEvent(chatId, deviceId, ChatEvent.MemberJoined(deviceId), nowMs)
        true
    }

    enum class RemoveResult { REMOVED, NOT_MEMBER, OWNER_MUST_TRANSFER, LAST_MEMBER_DELETED }

    /**
     * Removes [deviceId] from the chat: a leave, or by [actorId] when [byAdmin]. An owner can't leave until ownership is
     * handed over, but the admin may remove the owner, which passes ownership to the longest-standing remaining member.
     * The last member going deletes the chat. The change is recorded as a chat event authored by [actorId].
     */
    @Synchronized
    fun removeChatMember(chatId: String, deviceId: String, byAdmin: Boolean, nowMs: Long, actorId: String = deviceId): RemoveResult = transaction {
        val chat = chat(chatId) ?: return@transaction RemoveResult.NOT_MEMBER
        if (!isChatMember(chatId, deviceId)) return@transaction RemoveResult.NOT_MEMBER
        if (chat.ownerId == deviceId && !byAdmin) return@transaction RemoveResult.OWNER_MUST_TRANSFER
        update("DELETE FROM chat_members WHERE chat_id=? AND device_id=?", chatId, deviceId)
        update("DELETE FROM chat_invites WHERE chat_id=? AND invitee_id=?", chatId, deviceId)
        if (byAdmin) update("DELETE FROM chat_invites WHERE chat_id=? AND inviter_id=?", chatId, deviceId)
        if (afterMemberGone(chat, deviceId, byAdmin, actorId, nowMs)) RemoveResult.REMOVED else RemoveResult.LAST_MEMBER_DELETED
    }

    /** Record that [deviceId] is no longer in [chat] and hand over ownership if needed; deletes the chat and returns false if nobody is left. */
    private fun afterMemberGone(chat: ChatRow, deviceId: String, removed: Boolean, actorId: String, nowMs: Long): Boolean {
        val remaining = chatMembers(chat.id)
        if (remaining.isEmpty()) { deleteChat(chat.id); return false }
        recordEvent(chat.id, actorId, if (removed) ChatEvent.MemberRemoved(deviceId, byAdmin = true) else ChatEvent.MemberLeft(deviceId), nowMs)
        if (chat.ownerId == deviceId) {
            val next = remaining.first().id
            update("UPDATE chats SET owner_id=? WHERE id=?", next, chat.id)
            recordEvent(chat.id, actorId, ChatEvent.OwnerChanged(next), nowMs)
        }
        return true
    }

    /** One UPDATE guarded by the current owner and the target's membership; records the change. */
    @Synchronized
    fun transferChatOwner(chatId: String, from: String, to: String, nowMs: Long): Boolean = transaction {
        val changed = update(
            """UPDATE chats SET owner_id=? WHERE id=? AND owner_id=?
               AND EXISTS(SELECT 1 FROM chat_members WHERE chat_id=? AND device_id=?)""",
            to, chatId, from, chatId, to,
        ) > 0
        if (changed) recordEvent(chatId, from, ChatEvent.OwnerChanged(to), nowMs)
        changed
    }

    private fun inviteExpired(inv: ChatInviteRow, chat: ChatRow, nowMs: Long): Boolean =
        nowMs >= inv.createdAtMs + Limits.CHAT_INVITE_TTL_MS ||
            !isSpaceMember(chat.spaceId, inv.inviteeId) || !isSpaceMember(chat.spaceId, inv.inviterId)

    private fun invite(id: String): ChatInviteRow? =
        conn.prepareStatement("SELECT * FROM chat_invites WHERE id=?").use {
            it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) rs.toInvite() else null }
        }

    /**
     * An invite by chat member [inviterId] of space member [inviteeId]; APPROVED at once when the owner invites,
     * else PENDING_APPROVAL. Null when the chat is gone, the inviter is not in the chat, the invitee is not in the
     * space or is already in the chat or already has a live invite. Finished (denied, declined, expired) invites are replaced.
     */
    @Synchronized
    fun createInvite(chatId: String, inviteeId: String, inviterId: String, nowMs: Long): ChatInviteRow? = transaction {
        val chat = chat(chatId) ?: return@transaction null
        if (!isChatMember(chatId, inviterId) || !isSpaceMember(chat.spaceId, inviterId)) return@transaction null
        if (!isSpaceMember(chat.spaceId, inviteeId) || isChatMember(chatId, inviteeId)) return@transaction null
        val existing = conn.prepareStatement("SELECT * FROM chat_invites WHERE chat_id=? AND invitee_id=?").use {
            it.setString(1, chatId); it.setString(2, inviteeId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toInvite()) } }
        }
        val live = existing.any { (it.state == InviteState.PENDING_APPROVAL || it.state == InviteState.APPROVED) && !inviteExpired(it, chat, nowMs) }
        if (live) return@transaction null
        existing.forEach { update("DELETE FROM chat_invites WHERE id=?", it.id) }
        val state = if (chat.ownerId == inviterId) InviteState.APPROVED else InviteState.PENDING_APPROVAL
        val row = ChatInviteRow(newId(), chatId, inviteeId, inviterId, state, nowMs)
        update("INSERT INTO chat_invites(id,chat_id,invitee_id,inviter_id,state,created_at) VALUES(?,?,?,?,?,?)", row.id, chatId, inviteeId, inviterId, state.name, nowMs)
        row
    }

    @Synchronized
    fun chatInvite(id: String): ChatInviteRow? = invite(id)

    /** Invites [deviceId] can act on: approvals it owes as chat owner, and its own APPROVED invitations; never expired or stale ones. */
    @Synchronized
    fun invitesFor(deviceId: String, nowMs: Long): List<ChatInviteRow> =
        conn.prepareStatement(
            """SELECT i.* FROM chat_invites i JOIN chats c ON c.id=i.chat_id
               WHERE (i.state='PENDING_APPROVAL' AND c.owner_id=?) OR (i.state='APPROVED' AND i.invitee_id=?)
               ORDER BY i.created_at, i.id"""
        ).use {
            it.setString(1, deviceId); it.setString(2, deviceId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toInvite()) } }
        }.filter { inv ->
            val chat = chat(inv.chatId)
            chat != null && !inviteExpired(inv, chat, nowMs) && !isChatMember(inv.chatId, inv.inviteeId)
        }

    /**
     * Moves the invite from [from] to [to] only if it is still in [from], the move is allowed (PENDING_APPROVAL to
     * APPROVED or DENIED, APPROVED to DECLINED) and the invite has not expired.
     */
    @Synchronized
    fun setInviteState(id: String, from: InviteState, to: InviteState, nowMs: Long): Boolean = transaction {
        val allowed = (from == InviteState.PENDING_APPROVAL && (to == InviteState.APPROVED || to == InviteState.DENIED)) ||
            (from == InviteState.APPROVED && to == InviteState.DECLINED)
        if (!allowed) return@transaction false
        val inv = invite(id) ?: return@transaction false
        val chat = chat(inv.chatId) ?: return@transaction false
        if (inv.state != from || inviteExpired(inv, chat, nowMs)) return@transaction false
        update("UPDATE chat_invites SET state=? WHERE id=? AND state=?", to.name, id, from.name) > 0
    }

    /** Turns [deviceId]'s unexpired APPROVED invite into membership (and consumes it). */
    @Synchronized
    fun joinViaInvite(id: String, deviceId: String, nowMs: Long): Boolean = transaction {
        val inv = invite(id) ?: return@transaction false
        if (inv.inviteeId != deviceId || inv.state != InviteState.APPROVED) return@transaction false
        val chat = chat(inv.chatId) ?: return@transaction false
        if (inviteExpired(inv, chat, nowMs)) return@transaction false
        if (!addChatMember(chat.id, deviceId, nowMs)) return@transaction false
        update("DELETE FROM chat_invites WHERE id=?", id)
        true
    }

    /** Bumps and returns the chat's revision; call inside a transaction. */
    private fun nextRev(chatId: String): Long {
        update("UPDATE chats SET rev=rev+1 WHERE id=?", chatId)
        return conn.prepareStatement("SELECT rev FROM chats WHERE id=?").use {
            it.setString(1, chatId); it.executeQuery().use { rs -> check(rs.next()) { "no such chat" }; rs.getLong(1) }
        }
    }

    private fun bodyJson(text: String?) = MjolnirJson.encodeToString(StoredChatBody.serializer(), StoredChatBody(text))

    /** A real message (not a system entry) of [chatId]. */
    private fun chatMessage(chatId: String, id: String): ChatMessageRow? =
        conn.prepareStatement("SELECT * FROM chat_messages WHERE id=? AND chat_id=? AND event_json IS NULL").use {
            it.setString(1, id); it.setString(2, chatId); it.executeQuery().use { rs -> if (rs.next()) rs.toChatMessage() else null }
        }

    /** A real message (not a system entry) of [chatId], deleted or not. */
    @Synchronized
    fun message(chatId: String, id: String): ChatMessageRow? = chatMessage(chatId, id)

    /** The newest entry of [chatId] that is not deleted (a message or a system entry), for the chat list preview. */
    @Synchronized
    fun lastChatMessage(chatId: String): ChatMessageRow? =
        conn.prepareStatement("SELECT * FROM chat_messages WHERE chat_id=? AND deleted=0 ORDER BY rev DESC LIMIT 1").use {
            it.setString(1, chatId); it.executeQuery().use { rs -> if (rs.next()) rs.toChatMessage() else null }
        }

    /** Appends a message at a new chat revision; null when the chat is gone or [authorId] is not a member. */
    @Synchronized
    fun postMessage(chatId: String, authorId: String, text: String, nowMs: Long): ChatMessageRow? = transaction {
        if (!isChatMember(chatId, authorId)) return@transaction null
        val rev = nextRev(chatId)
        val id = newId()
        update("INSERT INTO chat_messages(id,chat_id,author_id,rev,sent_at,body_json) VALUES(?,?,?,?,?,?)", id, chatId, authorId, rev, nowMs, bodyJson(text))
        ChatMessageRow(id, chatId, authorId, rev, nowMs, null, false, false, text)
    }

    /** Appends a system entry at a new revision, authored by [actorId]. */
    private fun recordEvent(chatId: String, actorId: String, event: ChatEvent, nowMs: Long) {
        val rev = nextRev(chatId)
        update(
            "INSERT INTO chat_messages(id,chat_id,author_id,rev,sent_at,body_json,event_json) VALUES(?,?,?,?,?,?,?)",
            newId(), chatId, actorId, rev, nowMs, bodyJson(null), MjolnirJson.encodeToString(ChatEvent.serializer(), event),
        )
    }

    /** Replaces the text of [authorId]'s own undeleted message in [chatId]; false when it is not theirs, deleted or missing. */
    @Synchronized
    fun editMessage(chatId: String, id: String, authorId: String, text: String, nowMs: Long): Boolean = transaction {
        val m = chatMessage(chatId, id) ?: return@transaction false
        if (m.authorId != authorId || m.deleted) return@transaction false
        val rev = nextRev(m.chatId)
        update("UPDATE chat_messages SET rev=?, edited_at=?, body_json=? WHERE id=?", rev, nowMs, bodyJson(text), id)
        true
    }

    /** Tombstones the message (row kept, body dropped) when [byDeviceId] wrote it, or [byAdmin]; false if missing or already deleted. */
    @Synchronized
    fun deleteMessage(chatId: String, id: String, byDeviceId: String, byAdmin: Boolean): Boolean = transaction {
        val m = chatMessage(chatId, id) ?: return@transaction false
        if (m.deleted || (m.authorId != byDeviceId && !byAdmin)) return@transaction false
        val rev = nextRev(m.chatId)
        update(
            "UPDATE chat_messages SET rev=?, deleted=1, deleted_by_admin=?, body_json=? WHERE id=?",
            rev, if (byAdmin && m.authorId != byDeviceId) 1 else 0, bodyJson(null), id,
        )
        true
    }

    /** Messages with rev above [sinceRev] (edits and deletes included), oldest rev first, at most [limit]; true when more remain. */
    @Synchronized
    fun sync(chatId: String, sinceRev: Long, limit: Int = 200): Pair<List<ChatMessageRow>, Boolean> {
        val rows = conn.prepareStatement("SELECT * FROM chat_messages WHERE chat_id=? AND rev>? ORDER BY rev LIMIT ?").use {
            it.setString(1, chatId); it.setLong(2, sinceRev); it.setInt(3, limit + 1)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toChatMessage()) } }
        }
        return rows.take(limit) to (rows.size > limit)
    }

    /** Raises [deviceId]'s read marker in [chatId] to [rev] (never lowers it, never above the chat's rev). */
    @Synchronized
    fun markRead(chatId: String, deviceId: String, rev: Long) {
        update(
            "UPDATE chat_members SET read_rev=MAX(read_rev, MIN(?, (SELECT rev FROM chats WHERE id=?))) WHERE chat_id=? AND device_id=?",
            rev, chatId, chatId, deviceId,
        )
    }

    @Synchronized
    fun readRev(chatId: String, deviceId: String): Long =
        conn.prepareStatement("SELECT read_rev FROM chat_members WHERE chat_id=? AND device_id=?").use {
            it.setString(1, chatId); it.setString(2, deviceId); it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    /**
     * Cleans up after [deviceId] stopped being a member of [spaceId] (by [actingId]: itself, or the admin): it leaves the
     * space's chats (ownership goes to the longest-standing remaining member, a chat left empty is deleted) and its invites
     * in them, as invitee or inviter, go. Returns who to notify about each changed or deleted chat.
     */
    @Synchronized
    fun onLeftSpace(spaceId: String, deviceId: String, nowMs: Long, actingId: String = deviceId): LeftSpaceResult = transaction {
        val changed = LinkedHashMap<String, List<String>>()
        val deleted = LinkedHashMap<String, List<String>>()
        for (chat in chatsIn(spaceId)) {
            update("DELETE FROM chat_invites WHERE chat_id=? AND (invitee_id=? OR inviter_id=?)", chat.id, deviceId, deviceId)
            if (!isChatMember(chat.id, deviceId)) continue
            val before = chatMembers(chat.id).map { it.id }
            update("DELETE FROM chat_members WHERE chat_id=? AND device_id=?", chat.id, deviceId)
            if (afterMemberGone(chat, deviceId, actingId != deviceId, actingId, nowMs)) changed[chat.id] = before else deleted[chat.id] = before
        }
        LeftSpaceResult(changed, deleted)
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

    /** Ids of files delivered to [toId] that it has downloaded in full. */
    @Synchronized
    fun downloadedFileIdsTo(toId: String): List<String> =
        conn.prepareStatement(
            "SELECT f.id FROM files f JOIN deliveries d ON d.id=f.delivery_id WHERE d.to_id=? AND f.downloaded_at IS NOT NULL AND f.acked_at IS NULL ORDER BY f.downloaded_at, f.id"
        ).use {
            it.setString(1, toId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
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
        require(table in setOf("devices", "pairings", "links", "deliveries", "uploads", "files", "spaces", "space_members", "rooms", "chats", "chat_members", "chat_invites", "chat_messages")) { "unknown table" }
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
