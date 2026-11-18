package app.mjolnir.android.session

import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SwitchingRelayApiTest {
    @Test fun relayExceptionFromDelegateReachesCallerUnwrapped() = runTest {
        val fake = FakeRelayApi().apply { failAllWith = RelayException(RelayErrorKind.NETWORK, "down") }
        val api = SwitchingRelayApi { fake }
        val e = assertFailsWith<RelayException> { api.peers() }
        assertEquals(RelayErrorKind.NETWORK, e.kind)
    }

    @Test fun exceptionFromCurrentLookupReachesCaller() = runTest {
        val api = SwitchingRelayApi { throw RelayException(RelayErrorKind.SERVER, "boom") }
        assertFailsWith<RelayException> { api.me() }
    }

    @Test fun usesTheDelegateCurrentAtCallTime() = runTest {
        val a = FakeRelayApi().apply { peers += Peer("a", "A", DeviceKind.PC) }
        val b = FakeRelayApi().apply { peers += Peer("b", "B", DeviceKind.PC) }
        var use: FakeRelayApi = a
        val api = SwitchingRelayApi { use }
        assertEquals("a", api.peers().single().deviceId)
        use = b
        assertEquals("b", api.peers().single().deviceId)
    }
}
