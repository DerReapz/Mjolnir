package app.mjolnir.server

import app.mjolnir.protocol.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The guest page's Content-Security-Policy: same-origin everything, no inline script or style, never framed. */
internal const val GUEST_CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; " +
    "connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"

private val FONT_TTF = ContentType("font", "ttf")
private val UTF8_TEXT = ContentType.Text.Plain.withCharset(Charsets.UTF_8)

/** The only files served under `/guest/`, with their types; anything else is 404, so no path can reach other resources. */
private val GUEST_ASSETS: Map<String, ContentType> = mapOf(
    "app.js" to ContentType.Text.JavaScript.withCharset(Charsets.UTF_8),
    "app.css" to ContentType.Text.CSS.withCharset(Charsets.UTF_8),
    "hammer-white.png" to ContentType.Image.PNG,
    "fonts/Inter.ttf" to FONT_TTF,
    "fonts/SpaceGrotesk.ttf" to FONT_TTF,
    "fonts/OFL-Inter.txt" to UTF8_TEXT,
    "fonts/OFL-SpaceGrotesk.txt" to UTF8_TEXT,
)

private fun guestResource(path: String): ByteArray =
    checkNotNull(GuestPageResources::class.java.getResourceAsStream("/guest/$path")) { "missing guest resource $path" }
        .use { it.readBytes() }

/** Loaded once: the page and its assets ship inside the jar and never change while the relay runs. */
private object GuestPageResources {
    val index: ByteArray by lazy { guestResource("index.html") }
    val assets: Map<String, ByteArray> by lazy { GUEST_ASSETS.keys.associateWith(::guestResource) }
}

private fun ApplicationCall.guestHeaders() {
    response.header("Content-Security-Policy", GUEST_CSP)
    response.header("Referrer-Policy", "no-referrer") // the page URL holds the invite secret
    response.header("X-Content-Type-Options", "nosniff")
}

fun Route.guestPageRoutes() {
    // The same page for any secret: the page itself redeems it, so a wrong or old link learns nothing here.
    get("/g/{secret}") {
        call.guestHeaders()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondBytes(GuestPageResources.index, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }

    get("/guest/{path...}") {
        val path = call.parameters.getAll("path").orEmpty().joinToString("/")
        val type = GUEST_ASSETS[path] ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
        call.guestHeaders()
        // Script and style revalidate so a relay update never pairs a new page with an old script; the rest is static.
        val cache = if (type == FONT_TTF || type == ContentType.Image.PNG) "public, max-age=86400" else "no-cache"
        call.response.header(HttpHeaders.CacheControl, cache)
        call.respondBytes(GuestPageResources.assets.getValue(path), type)
    }
}
