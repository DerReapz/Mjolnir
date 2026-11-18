package app.mjolnir.client

import app.mjolnir.protocol.FileRef
import app.mjolnir.protocol.SendRequest
import app.mjolnir.protocol.SendResponse
import app.mjolnir.protocol.Tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class BatchesTest {
    private fun tabs(n: Int) = List(n) { Tab("https://example.com/$it") }
    private fun refs(n: Int) = List(n) { FileRef("f$it", "n$it", 1, "text/plain", "x") }

    @Test fun sendsInBatchesOfHundred() = runTest {
        val api = FakeRelayApi()
        val outcome = sendInBatches(api, "pc", tabs(250))
        assertEquals(listOf(100, 100, 50), api.sent.map { it.tabs.size })
        assertEquals(250, outcome.sentTabs.size)
    }

    @Test fun tabsAndFilesGoInSeparateDeliveries() = runTest {
        val api = FakeRelayApi()
        sendInBatches(api, "pc", tabs(150), refs(25))
        assertEquals(
            listOf(
                SendRequest("pc", tabs(150).take(100), emptyList()),
                SendRequest("pc", tabs(150).drop(100), emptyList()),
                SendRequest("pc", emptyList(), refs(25).map { it.id }),
            ),
            api.sent,
        )
    }

    @Test fun stopsAtFirstFailedBatch() = runTest {
        val api = FakeRelayApi()
        var calls = 0
        api.onSend = {
            if (++calls == 2) throw RelayException(RelayErrorKind.NETWORK, "down")
            SendResponse("x")
        }
        val outcome = sendInBatches(api, "pc", tabs(250))
        assertEquals(100, outcome.sentTabs.size)
        assertEquals(RelayErrorKind.NETWORK, outcome.error?.kind)
        assertEquals(2, calls)
    }
}
