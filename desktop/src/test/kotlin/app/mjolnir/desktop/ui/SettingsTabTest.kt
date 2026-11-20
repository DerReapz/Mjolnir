@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.mjolnir.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.mjolnir.desktop.SettingsDraft
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTabTest {
    private val draft = SettingsDraft("https://relay.example.com", "DESKTOP-4F2K", "C:\\Users\\you\\Downloads\\Mjolnir", true, true, true, true)

    @Test fun fourCardsAndTheBottomRow() = runComposeUiTest {
        setContent { MjolnirTheme { SettingsTabContent(draft, {}, null, RelayTest.Idle, {}, {}, null, {}, {}) } }
        for (title in listOf("Connection", "This PC", "Receiving", "Windows")) onAllNodesWithText(title, ignoreCase = true).assertCountEquals(1)
        onNodeWithText("This is the name your other devices and guests see.").assertExists()
        onNodeWithText("Open received links in my browser straight away").assertExists()
        onNodeWithText("Start Mjolnir when I sign in to Windows").assertExists()
        onNodeWithText("Show Mjolnir in Explorer's Send to menu").assertExists()
        onNodeWithText("Keep running in the tray when I close this window").assertExists()
        onNodeWithText("Mjolnir $APP_VERSION", substring = true).assertExists()
    }

    @Test fun testResultsAreShownAsMockedUp() = runComposeUiTest {
        var result by mutableStateOf<RelayTest>(RelayTest.Ok(38))
        setContent { MjolnirTheme { SettingsTabContent(draft, {}, null, result, {}, {}, null, {}, {}) } }
        onNodeWithText("Connected · 38 ms").assertExists()
        result = RelayTest.Failed("Couldn't reach the relay")
        onNodeWithText("Couldn't reach the relay").assertExists()
        result = RelayTest.Testing
        onNodeWithText("Testing…").assertExists()
    }

    @Test fun buttonsCallBack() = runComposeUiTest {
        var tests = 0; var saves = 0; var licences = 0
        setContent { MjolnirTheme { SettingsTabContent(draft, {}, null, RelayTest.Idle, { tests++ }, {}, null, { licences++ }, { saves++ }) } }
        onNodeWithText("Test").performClick(); onNodeWithText("Save").performClick(); onNodeWithText("Open-source licences").performClick()
        assertEquals(Triple(1, 1, 1), Triple(tests, saves, licences))
    }

    @Test fun idleStatusLineShowsTheLiveRelayStatus() = runComposeUiTest {
        var live by mutableStateOf(app.mjolnir.desktop.RelayStatus.CONNECTED)
        setContent { MjolnirTheme { SettingsTabContent(draft, {}, null, RelayTest.Idle, {}, {}, null, {}, {}, liveStatus = live) } }
        onNodeWithText("Connected").assertExists()
        live = app.mjolnir.desktop.RelayStatus.CONNECTING
        onNodeWithText("Connecting…").assertExists()
        live = app.mjolnir.desktop.RelayStatus.NOT_SET
        onNodeWithText("Relay not set").assertExists()
    }

    @Test fun afterATestTheResultReplacesTheLiveStatus() = runComposeUiTest {
        setContent { MjolnirTheme { SettingsTabContent(draft, {}, null, RelayTest.Ok(12), {}, {}, null, {}, {}, liveStatus = app.mjolnir.desktop.RelayStatus.CONNECTING) } }
        onNodeWithText("Connected · 12 ms").assertExists()
        onAllNodesWithText("Connecting…").assertCountEquals(0)
    }
}
