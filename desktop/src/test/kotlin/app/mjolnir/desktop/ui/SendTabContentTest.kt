@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import app.mjolnir.client.LocalUpload
import app.mjolnir.desktop.ItemPreview
import app.mjolnir.desktop.SendState
import app.mjolnir.desktop.detailLine
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.SpaceRef
import app.mjolnir.client.DestinationKey
import kotlin.test.Test
import kotlin.test.assertEquals

class SendTabContentTest {
    private val fold = Peer("fold", "Samsung Fold 3", DeviceKind.PHONE)
    private val photo = LocalUpload("/photos/IMG_2041.jpg", "IMG_2041.jpg", "image/jpeg", 4_200_000)
    private val photoPreview = ItemPreview.Photo("IMG_2041.jpg", 4_200_000, 4032, 3024, "/photos/IMG_2041.jpg")

    private fun queued(selected: Boolean = true): SendState {
        val s = SendState().withTargets(listOf(fold), null).copy(files = listOf(photo))
        return if (selected) s.toggle("/photos/IMG_2041.jpg") else s
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node: SemanticsNode = fetchSemanticsNode()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!(results)
        return results.single()
    }

    @Test fun sendButtonNamesTarget() = runComposeUiTest {
        setContent { MjolnirTheme { SendTabContent(queued(), nicknameOf = { it.name }, previews = mapOf(photo.path to photoPreview), onUpdate = {}) } }
        onNodeWithText("Send to Samsung Fold 3").assertIsEnabled()
    }

    @Test fun disabledWithNothingSelected() = runComposeUiTest {
        setContent { MjolnirTheme { SendTabContent(queued(selected = false), nicknameOf = { it.name }, onUpdate = {}) } }
        onNodeWithText("Send to Samsung Fold 3").assertIsNotEnabled()
        assertEquals(Forge.muted, onNodeWithText("Send to Samsung Fold 3", useUnmergedTree = true).layout().layoutInput.style.color)
    }

    @Test fun clickingRowShowsPreview() = runComposeUiTest {
        var state by mutableStateOf(queued())
        val detail = detailLine(photoPreview)
        setContent {
            MjolnirTheme {
                SendTabContent(state, nicknameOf = { it.name }, previews = mapOf(photo.path to photoPreview), onUpdate = { change -> state = change(state) })
            }
        }
        onAllNodesWithText(detail).assertCountEquals(1) // the row's own detail line
        onNodeWithText("IMG_2041.jpg").performClick()
        onAllNodesWithText(detail).assertCountEquals(2) // plus the preview panel
    }

    // a click applies to the queue as it is now, not to the snapshot the row was composed from
    @Test fun clickKeepsAConcurrentChangeToTheQueue() = runComposeUiTest {
        val composedFrom = queued()
        var owner = composedFrom // what the controller holds
        setContent {
            MjolnirTheme {
                SendTabContent(composedFrom, nicknameOf = { it.name }, previews = mapOf(photo.path to photoPreview), onUpdate = { change -> owner = change(owner) })
            }
        }
        owner = owner.withText("https://later.example") // changes after composition, e.g. a drop
        onNodeWithText("IMG_2041.jpg").performClick()
        assertEquals(listOf("https://later.example"), owner.links)
        assertEquals("/photos/IMG_2041.jpg", owner.selectedRow)
    }

    @Test fun singleGroupHeadingHidden() = runComposeUiTest {
        setContent { MjolnirTheme { SendTabContent(queued(), nicknameOf = { it.name }, onUpdate = {}) } }
        onAllNodesWithText("My devices", ignoreCase = true).assertCountEquals(0)
    }

    @Test fun cardsEllipsizeLongNames() = runComposeUiTest {
        val long = "N".repeat(40)
        setContent { MjolnirTheme { SendTabContent(queued(), nicknameOf = { long }, onUpdate = {}) } }
        // The card title and the button both carry the name; the card is the one with a layout result of one line.
        val lines = onAllNodesWithText(long, useUnmergedTree = true).fetchSemanticsNodes().map { n ->
            val out = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!(out)
            out.single().lineCount
        }
        assertEquals(listOf(1), lines)
    }

    // a device in two spaces has a card under each heading; clicking one picks that space, and both light up
    @Test fun groupedHeadingsAndClickPicksTheCardsSpace() = runComposeUiTest {
        val mine = Peer("pc", "Desk", DeviceKind.PC)
        val inFriends = Peer("f", "Fred", DeviceKind.PC, SpaceRef("s1", "Friends"))
        val inWork = Peer("f", "Fred", DeviceKind.PC, SpaceRef("s2", "Work"))
        var state by mutableStateOf(SendState().withTargets(listOf(mine, inFriends, inWork), null))
        setContent { MjolnirTheme { SendTabContent(state, nicknameOf = { it.name }, onUpdate = { change -> state = change(state) }) } }
        onNodeWithText("MY DEVICES").assertExists() // 12 px uppercase headings
        onNodeWithText("FRIENDS").assertExists()
        onNodeWithText("WORK").assertExists()
        onAllNodesWithText("Fred", useUnmergedTree = true).assertCountEquals(2)
        onAllNodesWithText("Fred", useUnmergedTree = true)[1].performClick()
        assertEquals(DestinationKey("f", "s2"), state.targetKey)
        assertEquals(listOf(inFriends, inWork), state.targets.filter { state.isSelected(it) })
    }
}
