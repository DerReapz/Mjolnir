@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.mjolnir.desktop.Kind
import app.mjolnir.desktop.ReceivedEntry
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceivedTabTest {
    private val zone = ZoneId.of("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun timeLabels() {
        val now = at(2026, 11, 20, 18, 0)
        assertEquals("Today, 14:32", formatReceivedTime(at(2026, 11, 20, 14, 32), now, zone))
        assertEquals("Yesterday, 09:05", formatReceivedTime(at(2026, 11, 19, 9, 5), now, zone))
        assertEquals("12 Nov, 23:59", formatReceivedTime(at(2026, 11, 12, 23, 59), now, zone))
        assertEquals("3 Mar 2025, 08:00", formatReceivedTime(at(2025, 3, 3, 8, 0), now, zone))
    }

    private val link = ReceivedEntry("1:0", "ph", "Pixel 8", 0, Kind.LINK, "Runes", "https://example.com/runes")
    private val file = ReceivedEntry("2:0", "ph", "Pixel 8", 0, Kind.FILE, "IMG_2041.jpg", "/save/IMG_2041.jpg")
    private val folder = ReceivedEntry("3:0", "pc", "Office-PC", 0, Kind.FOLDER, "Holiday 2026", "/save/Holiday 2026")

    @Test fun emptyHistoryExplainsItself() = runComposeUiTest {
        setContent { MjolnirTheme { ReceivedTabContent(emptyList(), { it.fromName }, { "" }, {}, {}, {}, null) } }
        onNodeWithText("Nothing received yet.", substring = true).assertExists()
        onNodeWithText("Open received files folder").assertExists()
    }

    @Test fun rowsShowSenderNicknameAndOfferTheRightActions() = runComposeUiTest {
        val opened = mutableListOf<ReceivedEntry>(); val shown = mutableListOf<ReceivedEntry>(); var folderOpened = 0
        setContent {
            MjolnirTheme {
                ReceivedTabContent(
                    listOf(link, file, folder), nicknameOf = { if (it.fromDeviceId == "ph") "Dad's phone" else it.fromName },
                    whenOf = { "Today, 10:00" }, onOpen = { opened += it }, onShowInFolder = { shown += it }, onOpenFolder = { folderOpened++ }, notice = "That file is no longer there.",
                )
            }
        }
        onAllNodesWithText("Dad's phone · Today, 10:00").assertCountEquals(2)
        onNodeWithText("Office-PC · Today, 10:00").assertExists()
        onNodeWithText("Runes").assertExists(); onNodeWithText("IMG_2041.jpg").assertExists()
        onAllNodesWithText("Open").assertCountEquals(3)
        onAllNodesWithText("Show in folder").assertCountEquals(2) // links have no folder
        onAllNodesWithText("Open")[1].performClick()
        onAllNodesWithText("Show in folder")[0].performClick()
        onNodeWithText("Open received files folder").performClick()
        assertEquals(listOf(file), opened); assertEquals(listOf(file), shown); assertEquals(1, folderOpened)
        onNodeWithText("That file is no longer there.").assertExists()
    }
}
