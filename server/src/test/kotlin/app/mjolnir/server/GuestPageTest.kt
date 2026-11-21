package app.mjolnir.server

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuestPageTest {
    private val csp = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; " +
        "connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"

    private fun HttpResponse.assertGuestHeaders() {
        assertEquals(csp, headers["Content-Security-Policy"])
        assertEquals("no-referrer", headers["Referrer-Policy"])
        assertEquals("nosniff", headers["X-Content-Type-Options"])
    }

    @Test
    fun servesPageForAnySecret() = relayTest {
        for (secret in listOf("abc", "K7Q-29P", "x".repeat(43), "not_a_real_room")) {
            val r = client.get("/g/$secret")
            assertEquals(HttpStatusCode.OK, r.status, secret)
            assertEquals(ContentType.Text.Html, r.contentType()?.withoutParameters())
            val html = r.bodyAsText()
            assertTrue("/guest/app.js" in html && "/guest/app.css" in html, "page links its assets")
            assertTrue(secret !in html, "the secret is never echoed into the page")
        }
    }

    @Test
    fun securityHeadersPresent() = relayTest {
        val r = client.get("/g/some-secret")
        r.assertGuestHeaders()
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun unknownGuestAssetIs404WithSecurityHeaders() = relayTest {
        val r = client.get("/guest/nope.js")
        assertEquals(HttpStatusCode.NotFound, r.status)
        r.assertGuestHeaders()
    }

    @Test
    fun assetsServedWithNosniff() = relayTest {
        val expected = mapOf(
            "app.js" to ContentType.Text.JavaScript,
            "app.css" to ContentType.Text.CSS,
            "hammer-white.png" to ContentType.Image.PNG,
            "fonts/Inter.ttf" to ContentType("font", "ttf"),
            "fonts/SpaceGrotesk.ttf" to ContentType("font", "ttf"),
            "fonts/OFL-Inter.txt" to ContentType.Text.Plain,
            "fonts/OFL-SpaceGrotesk.txt" to ContentType.Text.Plain,
        )
        for ((path, type) in expected) {
            val r = client.get("/guest/$path")
            assertEquals(HttpStatusCode.OK, r.status, path)
            assertEquals(type, r.contentType()?.withoutParameters(), path)
            assertTrue(r.readRawBytes().isNotEmpty(), path)
            r.assertGuestHeaders()
        }
        // Only the listed assets are served: no index, no other classpath resource, no walking out of the folder.
        for (path in listOf("index.html", "nope.js", "../logback.xml", "%2e%2e/logback.xml", "fonts/../app.js")) {
            assertEquals(HttpStatusCode.NotFound, client.get("/guest/$path").status, path)
        }
    }

    @Test
    fun pageUnder100KbExcludingFonts() {
        val dir = File("src/main/resources/guest")
        assertTrue(dir.isDirectory, "guest resources at ${dir.absolutePath}")
        val bytes = dir.walkTopDown().filter { it.isFile && "fonts" !in it.relativeTo(dir).path.split(File.separatorChar) }
            .sumOf { it.length() }
        assertTrue(bytes < 100 * 1024, "guest page is $bytes bytes before fonts")
    }
}
