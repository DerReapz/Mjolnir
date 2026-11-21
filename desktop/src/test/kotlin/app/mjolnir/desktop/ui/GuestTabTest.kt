@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.mjolnir.client.Exchanged
import app.mjolnir.client.GuestRoomUi
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import kotlin.test.Test
import kotlin.test.assertEquals

class GuestTabTest {
    private val link = "https://relay.example.com/g/K7Q-29P"
    private val guest = Peer("g1", "Guest", DeviceKind.GUEST)
    private fun open(guest: Peer? = this.guest, link: String = this.link, error: String? = null) =
        GuestRoomUi.Open("r1", link, 2_472_000L, guest, error) // 41:12 left at clock 0

    private fun androidx.compose.ui.test.ComposeUiTest.show(
        ui: GuestRoomUi, exchanged: List<Exchanged> = emptyList(),
        onOpen: () -> Unit = {}, onEnd: () -> Unit = {}, onCopy: () -> Unit = {},
        onOpenFolder: (String) -> Unit = {}, onAddFiles: () -> Unit = {}, onAddFolder: () -> Unit = {},
    ) = setContent {
        MjolnirTheme { GuestTabContent(ui, exchanged, onOpen, onEnd, onCopy, onAddFiles, onAddFolder, {}, onOpenFolder = onOpenFolder, clock = { 0L }) }
    }

    @Test fun closedShowsOpenButton() = runComposeUiTest {
        var opened = 0
        show(GuestRoomUi.Closed, onOpen = { opened++ })
        onNodeWithText("Open a guest room").performClick()
        assertEquals(1, opened)
        onNodeWithText("Copy link").assertDoesNotExist()
    }

    @Test fun openShowsLinkCopyQrCountdown() = runComposeUiTest {
        var copied = 0
        show(open(), onCopy = { copied++ })
        onNodeWithText(link).assertExists()
        onNodeWithText("41:12").assertExists()
        onNodeWithText("Guest connected").assertExists()
        onNodeWithText("Scan with a phone camera, or send the link.", substring = true).assertExists()
        onNodeWithText("Only this PC can exchange with the guest.").assertExists()
        onNodeWithText("Drop files or folders here to send them to the guest").assertExists()
        onNodeWithText("Copy link").performClick()
        assertEquals(1, copied)
    }

    @Test fun dropAreaWaitsForTheGuest() = runComposeUiTest {
        var adds = 0
        show(open(guest = null), onAddFiles = { adds++ }, onAddFolder = { adds++ })
        onNodeWithText("Waiting for the guest to open the link…").assertExists()
        onNodeWithText("Drop files or folders here to send them to the guest").assertDoesNotExist()
        onNodeWithText("Add files…").assertIsNotEnabled()
        onNodeWithText("Add folder…").assertIsNotEnabled()
        onNodeWithText("Add files…").performClick()
        assertEquals(0, adds)
    }

    @Test fun dropAreaEnabledOnceTheGuestJoined() = runComposeUiTest {
        var adds = 0
        show(open(), onAddFiles = { adds++ })
        onNodeWithText("Waiting for the guest to open the link…").assertDoesNotExist()
        onNodeWithText("Add files…").assertIsEnabled()
        onNodeWithText("Add files…").performClick()
        assertEquals(1, adds)
    }

    @Test fun blankLinkShowsRoomWithoutLinkOrQr() = runComposeUiTest {
        show(open(link = ""))
        onNodeWithText("Copy link").assertDoesNotExist()
        onNodeWithText("End room").assertExists()
    }

    @Test fun exchangedRowsLabelled() = runComposeUiTest {
        val opened = mutableListOf<String>()
        show(
            open(),
            listOf(
                Exchanged("a", true, "Wedding photos", "Folder · 318 files · 2.1 GB · saved to Downloads", null, false, "/dl/Wedding photos"),
                Exchanged("b", true, "ceremony.mp4", "1.3 of 3.8 GB · 34%", 0.34f, false, null),
                Exchanged("c", false, "Seating plan.pdf", "240 KB", null, true, null),
            ),
            onOpenFolder = { opened += it },
        )
        onAllNodesWithText("FROM GUEST").assertCountEquals(2)
        onAllNodesWithText("YOU SENT").assertCountEquals(1)
        onNodeWithText("downloaded by guest", substring = true).assertExists()
        onNodeWithText("1.3 of 3.8 GB · 34%").assertExists()
        onAllNodesWithText("Open folder").assertCountEquals(1) // not for the unfinished one, nor for sent items
        onNodeWithText("Open folder").performClick()
        assertEquals(listOf("/dl/Wedding photos"), opened)
    }

    @Test fun endAsksNothingAndCloses() = runComposeUiTest {
        var ended = 0
        show(open(), onEnd = { ended++ })
        onNodeWithText("End room").performClick()
        assertEquals(1, ended)
        onNodeWithText("Are you sure", substring = true).assertDoesNotExist()
    }
}
