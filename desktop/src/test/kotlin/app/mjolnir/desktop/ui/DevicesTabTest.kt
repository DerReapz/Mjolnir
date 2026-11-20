@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import app.mjolnir.client.groupDestinations
import app.mjolnir.desktop.AppController
import app.mjolnir.protocol.ClaimResponse
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.PairingState
import app.mjolnir.protocol.PairingStatus
import app.mjolnir.protocol.Peer
import app.mjolnir.client.FakeRelayApi
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class DevicesTabTest {
    private val fold = Peer("fold", "Samsung Fold 3", DeviceKind.PHONE)
    private val pc = Peer("pc", "Office-PC", DeviceKind.PC)
    @TempDir lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest fun tearDown() = scope.cancel()

    private fun controller(fake: FakeRelayApi): AppController {
        File(dir, "config.json").writeText("""{"relayUrl":"https://a.example","deviceId":"d","token":"t","deviceName":"This PC"}""")
        return AppController(dir, scope) { _, _ -> fake }
    }

    @Test fun showsRealNameUnderNickname() = runComposeUiTest {
        setContent {
            MjolnirTheme {
                DevicesTabContent(
                    groups = groupDestinations(listOf(fold, pc), null), nicknames = mapOf("fold" to "Dad's phone"),
                    onRename = {}, onUnpair = {}, onPair = {},
                )
            }
        }
        onAllNodesWithText("Dad's phone").assertCountEquals(1)
        onAllNodesWithText("Samsung Fold 3").assertCountEquals(1) // the real name sits under the nickname
        onAllNodesWithText("Office-PC").assertCountEquals(1) // no nickname: the name once, nothing under it
        onAllNodesWithText("My devices", ignoreCase = true).assertCountEquals(0) // single group: heading hidden
    }

    @Test fun cardButtonsReportThePeer() = runComposeUiTest {
        val renamed = mutableListOf<Peer>(); val unpaired = mutableListOf<Peer>(); var pairs = 0
        setContent {
            MjolnirTheme {
                DevicesTabContent(groupDestinations(listOf(fold), null), emptyMap(), { renamed += it }, { unpaired += it }, { pairs++ })
            }
        }
        onNodeWithText("Rename…").performClick()
        onNodeWithText("Unpair").performClick()
        onNodeWithText("Pair a device").performClick()
        assertEquals(listOf(fold), renamed); assertEquals(listOf(fold), unpaired); assertEquals(1, pairs)
    }

    @Test fun emptyListInvitesPairing() = runComposeUiTest {
        setContent { MjolnirTheme { DevicesTabContent(emptyList(), emptyMap(), {}, {}, {}) } }
        onNodeWithText("No devices are paired yet.").assertExists()
        onNodeWithText("Pair a device").assertExists()
    }

    @Test fun renameDialogSetsAndClearsTheNickname() = runComposeUiTest {
        val c = controller(FakeRelayApi().apply { peers += fold })
        runBlocking { c.refreshPeers() }
        setContent { MjolnirTheme { DevicesTab(c) } }
        onNodeWithText("Rename…").performClick()
        onNodeWithText("Rename Samsung Fold 3").assertExists()
        onNode(hasSetTextAction()).performTextInput("Dad's phone")
        onNodeWithText("Save").performClick()
        assertEquals("Dad's phone", c.nicknames.get("fold"))
        onNodeWithText("Rename Dad's phone").assertDoesNotExist() // closed
        onAllNodesWithText("Samsung Fold 3").assertCountEquals(1)

        onNodeWithText("Rename…").performClick()
        onNodeWithText("Rename Dad's phone").assertExists()
        onNodeWithText("Cancel").performClick()
        assertEquals("Dad's phone", c.nicknames.get("fold")) // cancel changes nothing

        onNodeWithText("Rename…").performClick()
        onNode(hasSetTextAction()).performTextClearance() // the field holds the old nickname; empty clears it
        onNodeWithText("Save").performClick()
        assertNull(c.nicknames.get("fold"))
        onAllNodesWithText("Samsung Fold 3").assertCountEquals(1)
    }

    @Test fun pairPanelShowsACodeThenSwitchesToEntry() = runComposeUiTest {
        val c = controller(FakeRelayApi().apply { pairingCodeResult = app.mjolnir.protocol.PairingCodeResponse("ABC234", Long.MAX_VALUE) })
        setContent { MjolnirTheme { DevicesTab(c, openPairing = true) } }
        waitUntilExactlyOneExists(androidx.compose.ui.test.hasText("ABC-234"), 10_000)
        onNodeWithText("Enter a code instead").performClick()
        onNodeWithText("Show a code instead").assertExists()
        onNodeWithText("Pair", ignoreCase = false).assertExists()
    }

    @Test fun enteringACodePairsAndSaysSo() = runComposeUiTest {
        val laptop = Peer("pc2", "Laptop", DeviceKind.PC)
        val fake = FakeRelayApi().apply {
            onClaim = { ClaimResponse("pair-1", laptop) }
            statuses += PairingStatus(PairingState.ACCEPTED, laptop)
            peers += laptop
        }
        val c = controller(fake)
        setContent { MjolnirTheme { DevicesTab(c, openPairing = true) } }
        waitUntilExactlyOneExists(androidx.compose.ui.test.hasText("Enter a code instead"), 10_000)
        onNodeWithText("Enter a code instead").performClick()
        onNode(hasSetTextAction()).performTextInput("abc-234")
        onNodeWithText("Pair").performClick()
        waitUntilExactlyOneExists(androidx.compose.ui.test.hasText("Paired with Laptop"), 20_000)
    }

    @Test fun openingWithPairingStartedCallsBack() = runComposeUiTest {
        val c = controller(FakeRelayApi())
        var shown = 0
        setContent { MjolnirTheme { DevicesTab(c, openPairing = true, onPairingOpened = { shown++ }) } }
        waitForIdle()
        assertEquals(1, shown)
        onNodeWithText("Close").assertExists() // the panel is open
    }
}
