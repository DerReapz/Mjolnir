package app.mjolnir.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.ClaimOutcome
import app.mjolnir.client.DestinationGroup
import app.mjolnir.client.Messages
import app.mjolnir.client.isPaired
import app.mjolnir.client.RelayErrorKind
import app.mjolnir.client.RelayException
import app.mjolnir.client.groupDestinations
import app.mjolnir.desktop.AppController
import app.mjolnir.desktop.couldntGetCode
import app.mjolnir.protocol.DeviceKind
import app.mjolnir.protocol.Limits
import app.mjolnir.protocol.PairingCode
import app.mjolnir.protocol.Peer
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface EntryStatus {
    data object Idle : EntryStatus
    data class Waiting(val name: String) : EntryStatus
    data class Paired(val name: String) : EntryStatus
    data class Failed(val message: String) : EntryStatus
}

/** `m:ss` for a remaining time in milliseconds (rounded up to whole seconds). */
fun formatRemaining(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * The Devices tab: paired devices with *Rename…* and *Unpair*, and the pairing flow inline. [openPairing] starts
 * with the pair panel open (the Send tab's *Pair a device* route); [onPairingOpened] tells the caller it did.
 */
@Composable
fun DevicesTab(c: AppController, openPairing: Boolean = false, onPairingOpened: () -> Unit = {}) {
    val peers by c.peers.collectAsState()
    val config by c.config.collectAsState()
    val nicknames by c.nicknames.changes.collectAsState()
    val spaces by c.spaces.spaces.collectAsState()
    val invite by c.spaces.invite.collectAsState()
    val join by c.spaces.join.collectAsState()
    val notice by c.spaces.notice.collectAsState()
    val presence by c.chats.presence.collectAsState()
    var pairing by remember { mutableStateOf(openPairing) }
    var renaming by remember { mutableStateOf<Peer?>(null) }
    var spaceDialog by remember { mutableStateOf<SpaceDialog?>(null) }
    LaunchedEffect(Unit) {
        c.refreshPeers()
        c.spaces.refresh()
        if (openPairing) onPairingOpened()
    }
    val actions = remember(c) {
        SpacesActions(
            onCreate = c.spaces::create, onJoin = { c.spaces.join(it) },
            onInvite = { c.spaces.invite(it.id) }, onDismissInvite = c.spaces::dismissInvite,
            onRemove = { space, member -> c.spaces.remove(space.id, member.deviceId) },
            onLeave = { c.spaces.leave(it.id) }, onRename = { space, name -> c.spaces.rename(space.id, name) },
            onDelete = { c.spaces.delete(it.id) }, onMakeAdmin = { space, member -> c.spaces.makeAdmin(space.id, member.deviceId) },
            onTakeOver = { c.spaces.takeOver(it.id) },
            onDismissNotice = c.spaces::dismissNotice, onDismissJoin = c.spaces::dismissJoin,
            onCreateChat = c::createChat,
        )
    }

    Box(Modifier.fillMaxSize()) {
        DevicesTabContent(
            // Space members are not paired: they are listed in their space's card, not here (Unpair would not apply).
            groups = groupDestinations(peers.filter { it.isPaired }, config.lastPhoneId) { nicknames.nicknameOr(it.deviceId, it.name) }, nicknames = nicknames,
            onRename = { renaming = it }, onUnpair = { c.startUnpair(it) }, onPair = { pairing = true },
            pairPanel = if (pairing) ({ PairPanel(c, onClose = { pairing = false }) }) else null,
            spacesSection = {
                SpacesSection(
                    spaces, selfId = config.deviceId, nicknameOf = { nicknames.nicknameOr(it.deviceId, it.name) },
                    invite = invite, notice = notice, onDialog = {
                        if (it == SpaceDialog.Join) c.spaces.dismissJoin()
                        spaceDialog = it
                    }, actions = actions, presence = presence,
                )
            },
        )
        renaming?.let { peer ->
            RenameDialog(
                shownName = nicknames.nicknameOr(peer.deviceId, peer.name), nickname = nicknames[peer.deviceId].orEmpty(), realName = peer.name,
                onSave = { text -> c.nicknames.set(peer.deviceId, text); renaming = null }, // empty clears it
                onCancel = { renaming = null },
            )
        }
        SpaceDialogHost(spaceDialog, join, actions, onClose = { spaceDialog = null })
    }
}

/** Stateless Devices tab, so it can be tested and rendered headless. [pairPanel], while set, shows above the list. */
@Composable
fun DevicesTabContent(
    groups: List<DestinationGroup>,
    nicknames: Map<String, String>,
    onRename: (Peer) -> Unit,
    onUnpair: (Peer) -> Unit,
    onPair: () -> Unit,
    pairPanel: (@Composable () -> Unit)? = null,
    /** The *Spaces* section ([SpacesSection]), shown after the paired devices. */
    spacesSection: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TabTitle("Devices", Modifier.weight(1f))
            if (pairPanel == null) ForgePrimaryButton("Pair a device", onClick = onPair)
        }
        pairPanel?.invoke()
        if (groups.all { it.peers.isEmpty() }) {
            Text("No devices are paired yet.", style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 14.sp, color = Forge.muted))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (group in groups) {
                group.title?.let { title -> item(key = "group:${group.key}") { ForgeSectionLabel(title) } }
                items(group.peers, key = { "${group.key}:${it.deviceId}" }) { peer ->
                    val nickname = nicknames[peer.deviceId]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).forgeCard(selected = false).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(nicknames.nicknameOr(peer.deviceId, peer.name), style = TextStyle(fontFamily = ForgeFonts.inter600, fontSize = 14.sp, color = Forge.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (nickname != null) Text(peer.name, style = MaterialTheme.typography.bodySmall.copy(color = Forge.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when (peer.kind) { DeviceKind.PHONE -> "Phone"; DeviceKind.PC -> "PC"; DeviceKind.GUEST -> "Guest" }, style = MaterialTheme.typography.bodySmall.copy(color = Forge.muted))
                        }
                        ForgeSecondaryButton("Rename…", onClick = { onRename(peer) })
                        ForgeSecondaryButton("Unpair", onClick = { onUnpair(peer) })
                    }
                }
            }
            spacesSection?.let { section -> item(key = "spaces") { section() } }
        }
    }
}

/** `Rename {name}`: a text field for the private nickname; empty clears it. */
@Composable
private fun RenameDialog(shownName: String, nickname: String, realName: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(nickname) }
    ForgeModal(onDismiss = onCancel) {
        Text("Rename $shownName", style = MaterialTheme.typography.titleMedium)
        ForgeInput(text, { text = it.take(MAX_NICKNAME_CHARS) }, placeholder = realName, onEnter = { onSave(text) })
        Text(
            "Only you see this name, on this PC. Leave it empty to use the device's own name.",
            style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 12.sp, color = Forge.muted),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            ForgeSecondaryButton("Cancel", onClick = onCancel)
            ForgePrimaryButton("Save", onClick = { onSave(text) })
        }
    }
}

private const val MAX_NICKNAME_CHARS = 40

/** The show-a-code / enter-a-code flow (what the old Pair window did), inline in the tab. [onClose] ends it. */
@Composable
private fun PairPanel(c: AppController, onClose: () -> Unit) {
    var generation by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }
    var deadline by remember { mutableLongStateOf(0L) }
    var remaining by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairedWith by remember { mutableStateOf<String?>(null) }
    var entering by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf<EntryStatus>(EntryStatus.Idle) }
    val panelScope = rememberCoroutineScope()
    val panelOpen = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { panelOpen.set(false) } }

    LaunchedEffect(generation) {
        code = null; error = null
        val api = c.api()
        val self = c.config.value.deviceName
        if (api == null) { error = couldntGetCode(Messages.failureReason(RelayErrorKind.NETWORK, self)); return@LaunchedEffect }
        try {
            val r = api.createPairingCode()
            // Count down locally from the moment the code arrived, so a skewed PC clock cannot shorten it.
            deadline = System.currentTimeMillis() + Limits.PAIRING_TTL_MS
            remaining = Limits.PAIRING_TTL_MS
            code = r.code
        } catch (e: RelayException) {
            error = couldntGetCode(Messages.failureReason(e, self))
        }
    }
    LaunchedEffect(code) {
        while (code != null) {
            remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(250)
        }
    }
    LaunchedEffect(Unit) {
        c.codeUsedUp.collect { generation++ } // a rejected request used the code up: get a fresh one
    }
    LaunchedEffect(Unit) {
        c.pairedWith.collect {
            pairedWith = it
            delay(1500)
            onClose()
        }
    }

    Column(Modifier.fillMaxWidth().forgeCard(selected = false).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ForgeSectionLabel("Pair a device")
            Spacer(Modifier.weight(1f))
            ForgeTextButton("Close", muted = true, onClick = onClose)
        }
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val paired = pairedWith
            val current = code
            val entered = entry
            when {
                entering -> {
                    when (entered) {
                        is EntryStatus.Waiting -> Text("Waiting for ${entered.name} to accept…")
                        is EntryStatus.Paired -> Text("Paired with ${entered.name}", style = MaterialTheme.typography.titleLarge)
                        else -> {
                            Text("Code from the other device", style = TextStyle(fontFamily = ForgeFonts.inter400, fontSize = 13.sp, color = Forge.muted))
                            ForgeInput(input, { input = it }, Modifier.width(260.dp), placeholder = "ABC-234")
                            (entered as? EntryStatus.Failed)?.let { Text(it.message, style = TextStyle(fontSize = 13.sp, color = Forge.error)) }
                            ForgePrimaryButton("Pair", enabled = input.isNotBlank(), onClick = {
                                entry = EntryStatus.Idle
                                // The claim runs in the controller's scope; this scope only waits for its outcome.
                                panelScope.launch {
                                    c.pairWithCode(input, deliver = { r ->
                                        // On the UI thread, where dispose also runs: the check and the write cannot interleave with a close.
                                        withContext(Dispatchers.Main) {
                                            if (!panelOpen.get()) return@withContext false
                                            entry = when (r) {
                                                is ClaimOutcome.Paired -> EntryStatus.Paired(r.peer.name)
                                                is ClaimOutcome.Failed -> EntryStatus.Failed(r.message)
                                            }
                                            true
                                        }
                                    }) { peer ->
                                        SwingUtilities.invokeLater { if (panelOpen.get()) entry = EntryStatus.Waiting(peer.name) }
                                    }
                                }
                            })
                        }
                    }
                }
                paired != null -> Text("Paired with $paired", style = MaterialTheme.typography.titleLarge)
                current != null && remaining > 0 -> {
                    ForgeCode(PairingCode.display(current))
                    ForgeBodyText("Expires in ${formatRemaining(remaining)}", muted = true)
                }
                current != null || error != null -> {
                    error?.let { Text(it) }
                    ForgePrimaryButton("Get a new code", onClick = { generation++ })
                }
                else -> Text("…")
            }
            if (paired == null && entered !is EntryStatus.Waiting && entered !is EntryStatus.Paired) {
                ForgeTextButton(if (entering) "Show a code instead" else "Enter a code instead",
                    onClick = { entering = !entering; entry = EntryStatus.Idle })
            }
        }
    }
}
