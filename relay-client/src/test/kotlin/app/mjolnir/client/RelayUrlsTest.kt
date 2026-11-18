package app.mjolnir.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayUrlsTest {
    @Test fun acceptsHttpsAndLocalDevHosts() {
        assertEquals("https://relay.example.com", RelayUrls.normalize(" https://relay.example.com/ "))
        listOf("http://localhost:8080", "http://127.0.0.1:8080", "http://10.0.2.2:8080").forEach { assertEquals(it, RelayUrls.normalize(it)) }
    }

    @Test fun rejectsPlainHttpAndJunk() =
        listOf("http://relay.example.com", "ftp://x", "", "relay.example.com", "https://").forEach { assertNull(RelayUrls.normalize(it), it) }
}
