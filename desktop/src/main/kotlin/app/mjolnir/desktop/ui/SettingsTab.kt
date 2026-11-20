package app.mjolnir.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.RelayCheck
import app.mjolnir.client.RelayUrls
import app.mjolnir.client.checkRelay
import app.mjolnir.desktop.AppController
import app.mjolnir.desktop.RELAY_CHANGE_WARNING
import app.mjolnir.desktop.SettingsDraft
import app.mjolnir.desktop.USE_HTTPS
import app.mjolnir.desktop.isValidDeviceName
import app.mjolnir.desktop.relayChanged
import app.mjolnir.desktop.toDraft
import app.mjolnir.desktop.validate
import java.awt.Component
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val APP_VERSION = "1.0"

/** What the relay field should show under it, or null. */
fun relayFieldMessage(input: String, current: String, attempted: Boolean): String? {
    val normalized = RelayUrls.normalize(input)
    return when {
        normalized == null -> if (attempted || input.isNotBlank()) USE_HTTPS else null
        current.isNotBlank() && normalized != current -> RELAY_CHANGE_WARNING
        else -> null
    }
}

/** Result of the *Test* button. */
sealed interface RelayTest {
    data object Idle : RelayTest
    data object Testing : RelayTest
    data class Ok(val millis: Long) : RelayTest
    data class Failed(val message: String) : RelayTest
}

/** The Settings tab: edits a draft that applies on Save; changing the relay asks first. [parent] anchors the folder chooser. */
@Composable
fun SettingsTab(c: AppController, parent: Component? = null) {
    val config by c.config.collectAsState()
    var draft by remember { mutableStateOf(config.toDraft()) }
    var test by remember { mutableStateOf<RelayTest>(RelayTest.Idle) }
    var attempted by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var confirmRelay by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun commit() {
        saving = true
        message = null
        c.saveSettings(draft) { error ->
            saving = false
            message = error ?: "Saved"
            messageIsError = error != null
            if (error == null) draft = draft.copy(relayUrl = RelayUrls.normalize(draft.relayUrl) ?: draft.relayUrl, deviceName = draft.deviceName.trim())
        }
    }
    fun save() {
        attempted = true
        val problem = draft.validate()
        when {
            problem != null -> { message = problem; messageIsError = true }
            draft.relayChanged(config) -> confirmRelay = true
            else -> commit()
        }
    }
    fun runTest() {
        testJob?.cancel()
        val typed = draft.relayUrl
        test = RelayTest.Testing
        testJob = scope.launch {
            // The address as typed, so it can be tested before saving; off the UI thread.
            test = when (val r = withContext(Dispatchers.IO) { checkRelay(typed) }) {
                is RelayCheck.Ok -> RelayTest.Ok(r.millis)
                is RelayCheck.Failed -> RelayTest.Failed(r.message)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        SettingsTabContent(
            draft = draft,
            onDraft = { next ->
                if (next.relayUrl != draft.relayUrl) { testJob?.cancel(); test = RelayTest.Idle; attempted = false }
                draft = next; message = null
            },
            relayMessage = relayFieldMessage(draft.relayUrl, config.relayUrl, attempted),
            test = test, onTest = ::runTest,
            onChangeFolder = {
                val chooser = JFileChooser(File(draft.saveDir)).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) draft = draft.copy(saveDir = chooser.selectedFile.absolutePath)
            },
            message = message, isError = messageIsError, saving = saving,
            onLicences = c::openLicences, onSave = ::save,
        )
        if (confirmRelay) ConfirmRelayChange(onCancel = { confirmRelay = false }, onConfirm = { confirmRelay = false; commit() })
    }
}

@Composable
private fun ConfirmRelayChange(onCancel: () -> Unit, onConfirm: () -> Unit) = ForgeModal(onDismiss = onCancel) {
    Text("Change the relay?", style = MaterialTheme.typography.titleMedium)
    Text(
        "$RELAY_CHANGE_WARNING You will need to pair them again.",
        style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 14.sp, color = Forge.muted),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        ForgeSecondaryButton("Cancel", onClick = onCancel)
        ForgePrimaryButton("Change relay", onClick = onConfirm)
    }
}

/** Stateless Settings tab (the `DesktopSettings` mockup), so it can be tested and rendered headless. */
@Composable
fun SettingsTabContent(
    draft: SettingsDraft,
    onDraft: (SettingsDraft) -> Unit,
    relayMessage: String?,
    test: RelayTest,
    onTest: () -> Unit,
    onChangeFolder: () -> Unit,
    message: String?,
    onLicences: () -> Unit,
    onSave: () -> Unit,
    isError: Boolean = true,
    saving: Boolean = false,
    version: String = APP_VERSION,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TabTitle("Settings")
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingsCard("Connection", Modifier.weight(1f).fillMaxHeight()) {
                    FieldLabel("Relay address")
                    ForgeInput(draft.relayUrl, { onDraft(draft.copy(relayUrl = it)) }, Modifier.fillMaxWidth(), placeholder = "https://", isError = relayMessage == USE_HTTPS)
                    relayMessage?.let { Hint(it, if (it == USE_HTTPS) Forge.error else Forge.accent) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val (dot, text) = when (test) {
                            RelayTest.Idle -> Forge.dashed to "Check that this address answers."
                            RelayTest.Testing -> Forge.accent to "Testing…"
                            is RelayTest.Ok -> STATUS_CONNECTED to "Connected · ${test.millis} ms"
                            is RelayTest.Failed -> Forge.error to test.message
                        }
                        Box(Modifier.size(8.dp).background(dot, CircleShape))
                        Text(text, Modifier.weight(1f), style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ForgeSecondaryButton("Test", onClick = onTest, enabled = test != RelayTest.Testing)
                    }
                }
                SettingsCard("This PC", Modifier.weight(1f).fillMaxHeight()) {
                    FieldLabel("Device name")
                    ForgeInput(draft.deviceName, { onDraft(draft.copy(deviceName = it)) }, Modifier.fillMaxWidth(), isError = !isValidDeviceName(draft.deviceName))
                    Hint("This is the name your other devices and guests see.", Forge.muted)
                }
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingsCard("Receiving", Modifier.weight(1f).fillMaxHeight()) {
                    FieldLabel("Save received files to")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForgeInput(draft.saveDir, { onDraft(draft.copy(saveDir = it)) }, Modifier.weight(1f))
                        ForgeSecondaryButton("Change…", onClick = onChangeFolder)
                    }
                    CheckRow("Open received links in my browser straight away", draft.openLinksOnArrival) { onDraft(draft.copy(openLinksOnArrival = it)) }
                }
                SettingsCard("Windows", Modifier.weight(1f).fillMaxHeight()) {
                    CheckRow("Start Mjolnir when I sign in to Windows", draft.launchAtLogin) { onDraft(draft.copy(launchAtLogin = it)) }
                    CheckRow("Show Mjolnir in Explorer's Send to menu", draft.sendToMenu) { onDraft(draft.copy(sendToMenu = it)) }
                    CheckRow("Keep running in the tray when I close this window", draft.keepInTray) { onDraft(draft.copy(keepInTray = it)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val small = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = Forge.muted)
            Text("Mjolnir $version ·", style = small)
            Text("Open-source licences", Modifier.clickable(onClick = onLicences).heightIn(min = 40.dp).padding(top = 12.dp), style = small.copy(color = Forge.accent))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                message?.let { Text(it, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = if (isError) Forge.error else Forge.muted), maxLines = 2) }
            }
            ForgePrimaryButton("Save", onClick = onSave, enabled = !saving, big = true)
        }
    }
}

/** A settings card: surface, 1 px border, radius 12, padding 16, small uppercase heading. */
@Composable
private fun SettingsCard(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.background(Forge.surface, Forge.cardShape).border(1.dp, Forge.border, Forge.cardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title.uppercase(), style = TextStyle(fontFamily = ForgeFonts.inter600, fontSize = 12.sp, letterSpacing = 1.sp, color = Forge.muted))
        content()
    }
}

@Composable
private fun FieldLabel(text: String) = Text(text, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.text))

@Composable
private fun Hint(text: String, color: Color) = Text(text, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = color))

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, colors = forgeCheckboxColors())
        Text(label, style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 14.sp, color = Forge.text))
    }
}
