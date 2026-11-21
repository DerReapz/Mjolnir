@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.mjolnir.desktop.InviteUi
import app.mjolnir.desktop.JoinUi
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Peer
import app.mjolnir.protocol.Space
import kotlin.test.Test
import kotlin.test.assertEquals

class SpacesSectionTest {
    private val me = Peer("me", "This PC", DeviceKind.PC)
    private val fred = Peer("fred", "Fred's phone", DeviceKind.PHONE)
    private val now = 1_700_000_000_000L
    private fun space(adminId: String, silent: Boolean = false) = Space("s1", "Friends", adminId, listOf(me, fred), silent)

    private fun androidx.compose.ui.test.ComposeUiTest.show(
        space: Space, invite: InviteUi? = null, actions: SpacesActions = SpacesActions(), dialog: ((SpaceDialog) -> Unit)? = null,
    ) = setContent {
        var open by mutableStateOf<SpaceDialog?>(null)
        MjolnirTheme {
            SpacesSection(listOf(space), "me", { it.name }, invite, null, { open = it; dialog?.invoke(it) }, actions, clock = { now })
            SpaceDialogHost(open, JoinUi.Idle, actions, onClose = { open = null })
        }
    }

    @Test fun adminCardOffersAdminActionsAndMarksTheAdmin() = runComposeUiTest {
        show(space("me"))
        onNodeWithText("Invite…").assertExists()
        onNodeWithText("Rename space").assertExists()
        onNodeWithText("Delete space").assertExists()
        onNodeWithText("Make admin").assertExists() // on Fred only, not on this device
        onNodeWithText("Remove").assertExists()
        onNodeWithText("Leave space").assertDoesNotExist()
        onNodeWithText("PC · admin").assertExists()
        onNodeWithText("Phone").assertExists()
    }

    @Test fun memberCardOffersLeaveAndTakeOverOnlyAfterThirtyDays() = runComposeUiTest {
        show(space("fred"))
        onNodeWithText("Leave space").assertExists()
        onNodeWithText("Take over as admin").assertDoesNotExist()
        onNodeWithText("Delete space").assertDoesNotExist()
        onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test fun silentAdminCanBeTakenOverAfterConfirming() = runComposeUiTest {
        var tookOver: Space? = null
        show(space("fred", silent = true), actions = SpacesActions(onTakeOver = { tookOver = it }))
        onNodeWithText("Take over as admin").performClick()
        assertEquals(null, tookOver) // asks first
        onNodeWithText("Take over Friends?").assertExists()
        onAllNodesWithText("Take over as admin").assertCountEquals(2) // the card's button and the dialog's
        onAllNodesWithText("Take over as admin")[1].performClick()
        assertEquals("s1", tookOver?.id)
    }

    @Test fun deleteNeedsAConfirmation() = runComposeUiTest {
        val deleted = mutableListOf<String>()
        show(space("me"), actions = SpacesActions(onDelete = { deleted += it.id }))
        onNodeWithText("Delete space").performClick()
        onNodeWithText("Cancel").performClick()
        assertEquals(emptyList(), deleted)
        onNodeWithText("Delete space").performClick()
        onAllNodesWithText("Delete space")[1].performClick()
        assertEquals(listOf("s1"), deleted)
    }

    @Test fun removeAndMakeAdminNeedAConfirmation() = runComposeUiTest {
        val log = mutableListOf<String>()
        show(space("me"), actions = SpacesActions(onRemove = { s, m -> log += "remove ${m.deviceId}" }, onMakeAdmin = { s, m -> log += "admin ${m.deviceId}" }))
        onNodeWithText("Remove").performClick()
        onNodeWithText("Remove Fred's phone from Friends?").assertExists()
        onAllNodesWithText("Remove")[1].performClick()
        onNodeWithText("Make admin").performClick()
        onAllNodesWithText("Make admin")[1].performClick()
        assertEquals(listOf("remove fred", "admin fred"), log)
    }

    @Test fun inviteShowsCodeAndCountdown() = runComposeUiTest {
        show(space("me"), invite = InviteUi("s1", "ABC234", now + 300_000))
        onNodeWithText("ABC-234").assertExists()
        onNodeWithText("Expires in 5:00").assertExists()
    }

    @Test fun expiredInviteOffersAFreshCode() = runComposeUiTest {
        var asked = 0
        show(space("me"), invite = InviteUi("s1", "ABC234", now - 1), actions = SpacesActions(onInvite = { asked++ }))
        onNodeWithText("Get a new code").performClick()
        assertEquals(1, asked)
    }

    @Test fun newSpaceDialogCreatesWithTheTypedName() = runComposeUiTest {
        val created = mutableListOf<String>()
        show(space("me"), actions = SpacesActions(onCreate = { created += it }))
        onNodeWithText("New space…").performClick()
        onNodeWithText("Create").performClick() // disabled while the name is empty
        assertEquals(emptyList(), created)
        onNode(hasSetTextAction()).performTextInput("Work")
        onNodeWithText("Create").performClick()
        assertEquals(listOf("Work"), created)
    }

    @Test fun newChatOnAnyCardCreatesInThatSpace() = runComposeUiTest {
        val created = mutableListOf<Pair<String, String>>()
        show(space("fred"), actions = SpacesActions(onCreateChat = { s, name -> created += s.id to name })) // a member's card, not the admin's
        onNodeWithText("New chat…").performClick()
        onNode(hasSetTextAction()).performTextInput("Holiday planning")
        onNodeWithText("Create").performClick()
        assertEquals(listOf("s1" to "Holiday planning"), created)
    }

    @Test fun joinDialogShowsTheFlowStates() = runComposeUiTest {
        var join by mutableStateOf<JoinUi>(JoinUi.Idle)
        val joined = mutableListOf<String>()
        setContent {
            var open by mutableStateOf<SpaceDialog?>(SpaceDialog.Join)
            MjolnirTheme { SpaceDialogHost(open, join, SpacesActions(onJoin = { joined += it }), onClose = { open = null }) }
        }
        onNode(hasSetTextAction()).performTextInput("abc-234")
        onNodeWithText("Join").performClick()
        assertEquals(listOf("abc-234"), joined)
        join = JoinUi.Waiting("Fred's PC")
        onNodeWithText("Waiting for Fred's PC to accept…").assertExists()
        join = JoinUi.Joined("Friends")
        onNodeWithText("Joined Friends").assertExists()
        join = JoinUi.Failed("That's a pairing code. Use Pair a device.")
        onNodeWithText("That's a pairing code. Use Pair a device.").assertExists()
    }
}
