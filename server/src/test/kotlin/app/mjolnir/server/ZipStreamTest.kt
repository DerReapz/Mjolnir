package app.mjolnir.server

import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.RelayEvent
import app.mjolnir.protocol.TicketRequest
import app.mjolnir.protocol.TicketResponse
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZipStreamTest {
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                out[e.name] = zin.readBytes()
            }
        }
        return out
    }

    @Test fun zipContainsFolderFiles() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val a = upload(phone, "Photos/a.jpg", ByteArray(300) { it.toByte() })
        val b = upload(phone, "Photos/sub/b.txt", "bee".toByteArray())
        val c = upload(phone, "Other/c.txt", "sea".toByteArray())
        val loose = upload(phone, "loose.txt", "x".toByteArray())
        sendFiles(phone, pc.deviceId, listOf(a.id, b.id, c.id, loose.id))
        val delivery = inbox(pc).single().id
        val r = client.get("/v1/files/zip?delivery=$delivery&folder=Photos") { bearerAuth(pc.token) }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("attachment; filename=\"Photos.zip\"", r.headers[HttpHeaders.ContentDisposition])
        assertEquals("application/zip", r.headers[HttpHeaders.ContentType])
        val entries = unzip(r.readRawBytes())
        assertEquals(listOf("Photos/a.jpg", "Photos/sub/b.txt"), entries.keys.toList())
        assertContentEquals(ByteArray(300) { it.toByte() }, entries["Photos/a.jpg"])
        assertContentEquals("bee".toByteArray(), entries["Photos/sub/b.txt"])
    }

    @Test fun zipEntriesUseStoredLevelDeflate() {
        val dir = createTempDirectory("zip").toFile()
        val f = java.io.File(dir, "a").apply { writeBytes(ByteArray(1000)) }
        val out = ByteArrayOutputStream()
        writeZip(out, listOf("T/a" to f))
        ZipInputStream(out.toByteArray().inputStream()).use { zin ->
            val e = zin.nextEntry!!
            assertEquals(ZipEntry.DEFLATED, e.method)
            assertContentEquals(ByteArray(1000), zin.readBytes())
        }
        assertTrue(out.size() > 1000, "level 0: nothing is recompressed")
    }

    @Test fun zipEntriesUseSafePaths() {
        assertEquals(
            listOf("Top/a.txt", "Top/a (2).txt", "Top/A (3).TXT", "Top/b", "Top/c", "Top/_CON", "Top/noext", "Top/noext (2)"),
            zipEntryNames(listOf("Top/a.txt", "Top/a.txt", "Top/A.TXT", "/Top/./b", "Top\\..\\c", "Top/CON", "Top/noext", "Top/noext")),
        )
        for (name in zipEntryNames(listOf("../../etc/passwd", "/abs/x", "C:\\win\\y", "a/../../z"))) {
            assertTrue(!name.startsWith("/") && name.split('/').none { it == ".." || it == "." } && ':' !in name, name)
        }
    }

    @Test fun zipOfRepeatedNamesKeepsEveryFile() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val one = upload(phone, "Top/same.txt", "1".toByteArray())
        val two = upload(phone, "Top/same.txt", "2".toByteArray())
        sendFiles(phone, pc.deviceId, listOf(one.id, two.id))
        val delivery = inbox(pc).single().id
        val entries = unzip(client.get("/v1/files/zip?delivery=$delivery&folder=Top") { bearerAuth(pc.token) }.readRawBytes())
        assertEquals(listOf("Top/same.txt", "Top/same (2).txt"), entries.keys.toList())
    }

    @Test fun zipOnlyForRecipient() = relayTest {
        val (pc, phone) = pairedPcAndPhone()
        val stranger = register("Stranger")
        val a = upload(phone, "Photos/a.jpg", "a".toByteArray())
        sendFiles(phone, pc.deviceId, listOf(a.id))
        val delivery = inbox(pc).single().id
        val url = "/v1/files/zip?delivery=$delivery&folder=Photos"
        assertEquals(HttpStatusCode.NotFound, client.get(url) { bearerAuth(phone.token) }.status, "not the sender")
        assertEquals(HttpStatusCode.NotFound, client.get(url) { bearerAuth(stranger.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(url).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/files/zip?delivery=$delivery&folder=Other") { bearerAuth(pc.token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/files/zip?delivery=$delivery") { bearerAuth(pc.token) }.status)
        assertEquals(HttpStatusCode.OK, client.get(url) { bearerAuth(pc.token) }.status)
    }

    @Test fun guestZipByTicketMarksTheFolderDownloaded() = relayTest {
        val owner = register("Desk", DeviceKind.PC)
        val (_, guest) = guestOf(owner)
        val a = upload(owner, "Album/a.jpg", "a".toByteArray())
        val b = upload(owner, "Album/b.jpg", "b".toByteArray())
        sendFiles(owner, guest.deviceId, listOf(a.id, b.id))
        val delivery = inbox(guest).single().id
        val other = register("Other", DeviceKind.PC)
        val stray = client.post("/v1/guest/ticket") {
            bearerAuth(other.token); contentType(ContentType.Application.Json); setBody(TicketRequest(deliveryId = delivery, folder = "Album"))
        }
        assertEquals(HttpStatusCode.NotFound, stray.status, "a ticket only for the recipient")
        val t = client.post("/v1/guest/ticket") {
            bearerAuth(guest.token); contentType(ContentType.Application.Json); setBody(TicketRequest(deliveryId = delivery, folder = "Album"))
        }.body<TicketResponse>().ticket
        withEvents(owner) {
            val r = client.get("/v1/files/zip?delivery=$delivery&folder=Album&t=$t")
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(listOf("Album/a.jpg", "Album/b.jpg"), unzip(r.readRawBytes()).keys.toList())
            assertEquals(RelayEvent.Downloaded(delivery, listOf(a.id, b.id)), next())
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/files/zip?delivery=$delivery&folder=Album&t=$t").status)
    }
}
