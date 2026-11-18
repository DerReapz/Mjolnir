package app.mjolnir.desktop

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class InstanceChannelTest {
    // Real sockets: waits run on a real-time dispatcher, always bounded.
    private suspend fun <T> realTime(ms: Long = 10_000, block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(ms) { block() } }

    // a second start while the first holds the lock hands its paths to the first and reports Forwarded
    @Test fun secondLaunchForwardsPathsToPrimary(@TempDir dir: File) = runTest {
        val primary = assertIs<Startup.Primary>(InstanceChannel.start(dir, emptyList()))
        try {
            assertEquals(Startup.Forwarded, InstanceChannel.start(dir, listOf("C:\\Users\\a\\report.pdf", "C:\\Users\\a\\Photos")))
            assertEquals(listOf("C:\\Users\\a\\report.pdf", "C:\\Users\\a\\Photos"), realTime { primary.incoming.first() })
        } finally {
            primary.lock.close()
        }
    }

    // the primary's own launch arguments are the first thing `incoming` emits
    @Test fun primaryEmitsItsOwnLaunchArgs(@TempDir dir: File) = runTest {
        val primary = assertIs<Startup.Primary>(InstanceChannel.start(dir, listOf("C:\\x.txt")))
        try {
            assertEquals(listOf("C:\\x.txt"), realTime { primary.incoming.first() })
        } finally {
            primary.lock.close()
        }
    }

    // a request with the wrong token gets "denied" and nothing reaches `incoming`
    @Test fun wrongTokenIsDenied(@TempDir dir: File) = runTest {
        val primary = assertIs<Startup.Primary>(InstanceChannel.start(dir, emptyList()))
        try {
            val port = Json.parseToJsonElement(File(dir, "ipc.json").readText()).jsonObject["port"]!!.jsonPrimitive.int
            val reply = realTime {
                Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                    s.soTimeout = 5_000
                    s.getOutputStream().write("{\"token\":\"not-the-token\",\"paths\":[\"C:\\\\evil.exe\"]}\n".toByteArray())
                    s.getOutputStream().flush()
                    s.getInputStream().bufferedReader().readLine()
                }
            }
            assertEquals("denied", reply)
            val emitted = withContext(Dispatchers.Default) { withTimeoutOrNull(500) { primary.incoming.first() } }
            assertNull(emitted)
        } finally {
            primary.lock.close()
        }
    }

    // lock held elsewhere and ipc.json pointing at a closed port: the second start gives up with Failed
    @Test fun staleChannelFileMeansFailed(@TempDir dir: File) {
        val lock = assertNotNull(SingleInstanceLock.tryAcquire(dir))
        try {
            val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            File(dir, "ipc.json").writeText("{\"port\":$closedPort,\"token\":\"abc\"}")
            assertEquals(Startup.Failed, InstanceChannel.start(dir, listOf("C:\\x.txt")))
        } finally {
            lock.close()
        }
    }

    // the channel listens on loopback only and ipc.json carries a 32-byte base64url token
    @Test fun channelIsLoopbackOnlyWithStrongToken(@TempDir dir: File) {
        val primary = assertIs<Startup.Primary>(InstanceChannel.start(dir, emptyList()))
        try {
            val info = Json.parseToJsonElement(File(dir, "ipc.json").readText()).jsonObject
            val token = info["token"]!!.jsonPrimitive.content
            assertEquals(32, java.util.Base64.getUrlDecoder().decode(token).size)
            assertTrue(assertNotNull(primary.boundAddress).isLoopbackAddress)
        } finally {
            primary.lock.close()
        }
    }

    // closing the primary releases the lock, so the next start becomes primary again
    @Test fun closingPrimaryReleasesLock(@TempDir dir: File) {
        assertIs<Startup.Primary>(InstanceChannel.start(dir, emptyList())).lock.close()
        assertIs<Startup.Primary>(InstanceChannel.start(dir, emptyList())).lock.close()
    }
}
