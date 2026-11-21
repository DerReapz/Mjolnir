package app.mjolnir.server

import app.mjolnir.protocol.Receipt
import app.mjolnir.protocol.CodeKind
import app.mjolnir.protocol.DeviceKind
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpacesStoreTest {
    private val store = Store("jdbc:sqlite::memory:")
    private var n = 0
    private fun device(name: String = "d${n++}") = store.createDevice(name, DeviceKind.PC, "hash-$name-${n++}", 1000L)

    @Test fun createAddsAdminAsMember() {
        val a = device()
        val s = store.createSpace(a.id, "Home", 10)
        assertEquals(a.id, s.adminId)
        assertEquals("Home", store.space(s.id)?.name)
        assertEquals(listOf(a.id), store.members(s.id).map { it.id })
        assertEquals(listOf(s.id), store.spacesOf(a.id).map { it.id })
    }

    @Test fun membersOrderedByJoin() {
        val a = device(); val b = device(); val c = device()
        val s = store.createSpace(a.id, "Home", 10)
        store.addMember(s.id, c.id, 30)
        store.addMember(s.id, b.id, 20)
        assertEquals(listOf(a.id, b.id, c.id), store.members(s.id).map { it.id })
        assertTrue(store.shareSpace(b.id, c.id, s.id))
    }

    @Test fun removeMember() {
        val a = device(); val b = device(); val o = device()
        val s = store.createSpace(a.id, "Home", 10)
        store.addMember(s.id, b.id, 20)
        assertEquals(Store.Removed.REMOVED, store.removeMember(s.id, b.id, a.id, 500).result)
        assertEquals(Store.Removed.NOT_MEMBER, store.removeMember(s.id, b.id, a.id, 500).result)
        assertEquals(listOf(a.id), store.members(s.id).map { it.id })
        assertFalse(store.shareSpace(a.id, b.id, s.id))
        assertFalse(store.shareSpace(a.id, o.id, s.id))
        assertTrue(store.spacesOf(b.id).isEmpty())
    }

    @Test fun deleteReturnsMembers() {
        val a = device(); val b = device()
        val s = store.createSpace(a.id, "Home", 10)
        store.addMember(s.id, b.id, 20)
        assertEquals(setOf(a.id, b.id), store.deleteSpace(s.id, a.id)!!.memberIds.toSet())
        assertNull(store.space(s.id))
        assertTrue(store.members(s.id).isEmpty())
        assertTrue(store.spacesOf(a.id).isEmpty())
    }

    @Test fun renameSpace() {
        val a = device()
        val s = store.createSpace(a.id, "Home", 10)
        assertTrue(store.renameSpace(s.id, a.id, "Office"))
        assertEquals("Office", store.space(s.id)?.name)
    }

    @Test fun transferIsAtomic() {
        val a = device(); val b = device(); val c = device()
        val s = store.createSpace(a.id, "Home", 10)
        store.addMember(s.id, b.id, 20); store.addMember(s.id, c.id, 30)
        val pool = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        val f1 = pool.submit<Boolean> { go.await(); store.transferAdmin(s.id, a.id, b.id) }
        val f2 = pool.submit<Boolean> { go.await(); store.transferAdmin(s.id, a.id, c.id) }
        go.countDown()
        val results = listOf(f1.get(), f2.get())
        pool.shutdown()
        assertEquals(1, results.count { it })
        assertEquals(if (f1.get()) b.id else c.id, store.space(s.id)?.adminId)
    }

    @Test fun invitePerSpaceDoesNotReplacePairingCode() {
        val a = device()
        val s1 = store.createSpace(a.id, "One", 10)
        val s2 = store.createSpace(a.id, "Two", 10)
        store.createPairing(a.id, "PAIR1", 100)
        store.createPairing(a.id, "INV1", 100, CodeKind.SPACE_INVITE, s1.id)
        store.createPairing(a.id, "INV2", 100, CodeKind.SPACE_INVITE, s2.id)
        assertNotNull(store.openPairingByCode("PAIR1", 100))
        assertNotNull(store.openPairingByCode("INV1", 100))
        assertNotNull(store.openPairingByCode("INV2", 100))
        store.createPairing(a.id, "INV1B", 100, CodeKind.SPACE_INVITE, s1.id)
        assertNull(store.openPairingByCode("INV1", 100))
        assertNotNull(store.openPairingByCode("INV1B", 100))
        assertNotNull(store.openPairingByCode("INV2", 100))
        store.createPairing(a.id, "PAIR2", 100)
        assertNull(store.openPairingByCode("PAIR1", 100))
        assertNotNull(store.openPairingByCode("INV1B", 100))
        val inv = store.openPairingByCode("INV1B", 100)!!
        assertEquals(CodeKind.SPACE_INVITE, inv.kind)
        assertEquals(s1.id, inv.spaceId)
        assertEquals(CodeKind.PAIRING, store.openPairingByCode("PAIR2", 100)!!.kind)
    }

    @Test fun upgradeAddsColumnsToOldDb() {
        val file = createTempDirectory().resolve("old.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use {
                it.executeUpdate("CREATE TABLE devices(id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, push_token TEXT NULL, created_at INTEGER NOT NULL)")
                it.executeUpdate("CREATE TABLE pairings(id TEXT PRIMARY KEY, code TEXT NOT NULL, owner_id TEXT NOT NULL, claimer_id TEXT NULL, state TEXT NOT NULL, expires_at INTEGER NOT NULL)")
                it.executeUpdate("CREATE TABLE deliveries(id TEXT PRIMARY KEY, from_id TEXT NOT NULL, to_id TEXT NOT NULL, tabs_json TEXT NOT NULL, sent_at INTEGER NOT NULL)")
                it.executeUpdate("INSERT INTO devices VALUES('d1','Old','PC','h1',NULL,5)")
                it.executeUpdate("INSERT INTO pairings VALUES('p1','CODE','d1',NULL,'PENDING',9999999999999)")
                it.executeUpdate("INSERT INTO deliveries VALUES('x1','d1','d2','[]',7)")
                // files as it was before tombstones and guest rooms (no acked_at, no downloaded_at); no rooms table.
                it.executeUpdate("CREATE TABLE files(id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, name TEXT NOT NULL, relative_path TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, created_at INTEGER NOT NULL, delivery_id TEXT NULL, sent_to TEXT NULL)")
                it.executeUpdate("INSERT INTO files VALUES('f1','d1','a.txt','a.txt','text/plain',3,'abc',5,NULL,NULL)")
                // chat tables as an earlier build might have left them: no removed_by_admin, no deleted_by_admin
                it.executeUpdate("CREATE TABLE chat_members(chat_id TEXT NOT NULL, device_id TEXT NOT NULL, joined_at INTEGER NOT NULL, read_rev INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(chat_id, device_id))")
            }
        }
        val s = Store("jdbc:sqlite:$file")
        assertEquals("Old", s.device("d1")?.name)
        assertEquals(0L, s.lastSeen("d1"))
        val p = s.openPairingByCode("CODE", 100)!!
        assertEquals(CodeKind.PAIRING, p.kind)
        assertNull(p.spaceId)
        val d = s.inbox("d2", 8).single()
        assertEquals("x1", d.id)
        assertNull(d.spaceId)
        assertNull(s.downloadedAt("f1"))
        assertTrue(s.markDownloaded("f1", 42))
        assertEquals(42L, s.downloadedAt("f1"))
        val room = s.createRoom("d1", "hash", 100, 1_000)!!
        assertEquals(room, s.openRoomOf("d1", 100))
        assertEquals(1, s.rowCount("rooms"))
        assertEquals(0, s.rowCount("chats")) // upgradeAddsChatTables
        val space = s.createSpace("d1", "S", 10)
        val chat = s.createChat(space.id, "d1", "Plans", 11)!!
        assertTrue(s.isChatMember(chat.id, "d1"))
        val msg = s.postMessage(chat.id, "d1", "hi", 12)!!
        assertTrue(s.deleteMessage(chat.id, msg.id, "d1", false))
        assertEquals(1, s.rowCount("chat_messages"))
        // chat_members from before receipts gains seen_rev, seen_at and read_at in place
        assertTrue(s.markSeen(chat.id, "d1", 99, 13))
        assertEquals(listOf(Receipt("d1", 2, 0, 13, null)), s.receipts(chat.id))
        assertEquals(listOf(app.mjolnir.protocol.MessageReceipt("d1", 13, null)), s.messageReceipts(chat.id, 2)) // chat_receipt_marks created in place
        // reopening an already-upgraded db is a no-op
        Store("jdbc:sqlite:$file")
    }

    @Test fun touchLastSeenThrottled() {
        val a = device()
        assertEquals(0L, store.lastSeen(a.id))
        store.touchLastSeen(a.id, 100_000)
        assertEquals(100_000L, store.lastSeen(a.id))
        store.touchLastSeen(a.id, 130_000)
        assertEquals(100_000L, store.lastSeen(a.id))
        store.touchLastSeen(a.id, 160_001)
        assertEquals(160_001L, store.lastSeen(a.id))
    }

    // Interleaving: the route read "B is a plain member" and "A is admin", then A's transfer lands first.
    @Test fun leaveAfterBecomingAdminIsRefused() {
        val a = device(); val b = device()
        val s = store.createSpace(a.id, "Home", 10)
        store.addMember(s.id, b.id, 20)
        assertTrue(store.transferAdmin(s.id, a.id, b.id))
        assertEquals(Store.Removed.ADMIN_MUST_TRANSFER, store.removeMember(s.id, b.id, b.id, 500).result)
        assertEquals(Store.Removed.ADMIN_MUST_TRANSFER, store.removeMember(s.id, b.id, a.id, 500).result)
        assertEquals(listOf(a.id, b.id), store.members(s.id).map { it.id })
    }

    // Interleaving: the route saw the target as a member, then the target left before the transfer.
    @Test fun transferToDepartedMemberIsRefused() {
        val a = device(); val b = device()
        val s = store.createSpace(a.id, "Home", 10)
        store.addMember(s.id, b.id, 20)
        assertEquals(Store.Removed.REMOVED, store.removeMember(s.id, b.id, b.id, 500).result)
        assertFalse(store.transferAdmin(s.id, a.id, b.id))
        assertEquals(a.id, store.space(s.id)!!.adminId)
    }

    @Test fun formerAdminCannotRenameDeleteOrRemove() {
        val a = device(); val b = device(); val c = device()
        val s = store.createSpace(a.id, "Home", 10)
        store.addMember(s.id, b.id, 20); store.addMember(s.id, c.id, 30)
        assertTrue(store.transferAdmin(s.id, a.id, b.id)) // lands after A's checks passed
        assertFalse(store.renameSpace(s.id, a.id, "Mine"))
        assertEquals(Store.Removed.NOT_ADMIN, store.removeMember(s.id, c.id, a.id, 500).result)
        assertNull(store.deleteSpace(s.id, a.id))
        assertEquals("Home", store.space(s.id)!!.name)
        assertEquals(3, store.members(s.id).size)
    }
}
