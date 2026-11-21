package app.mjolnir.android.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.mjolnir.android.send.OutgoingStatus
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.mjolnir.android.send.scheduleOutgoing
import app.mjolnir.android.data.TrayItem
import app.mjolnir.android.send.outgoingStatus
import app.mjolnir.android.send.rowTitle
import app.mjolnir.android.send.sendNow
import app.mjolnir.android.send.throwWorkFinished
import app.mjolnir.android.send.uniqueThrowWork
import app.mjolnir.client.OutgoingFiles
import app.mjolnir.client.destinationKey
import app.mjolnir.android.share.UriImporter
import app.mjolnir.android.throwing.FlickSensor
import app.mjolnir.android.throwing.ThrowDetector
import app.mjolnir.client.Messages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThrowScreen(
    vm: ThrowViewModel,
    importer: UriImporter,
    onPairDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Sensor: the detector and sensor live as long as the screen; listening follows the lifecycle (warm-up for the
    // accelerometer fallback starts when the screen is visible).
    val detector = remember { ThrowDetector(state.sensitivity) }
    LaunchedEffect(state.sensitivity) { detector.sensitivity = state.sensitivity }
    LaunchedEffect(Unit) { // entering the screen: Settings may have changed the sensitivity, Devices the pairings
        vm.refreshSensitivity()
        vm.refreshTargets()
    }
    val currentVm by rememberUpdatedState(vm)
    val sensor = remember {
        lateinit var s: FlickSensor
        s = FlickSensor(context, detector) {
            if (currentVm.state.value.phase == ThrowPhase.Armed) {
                currentVm.onFlick()
                s.stop()
                confirmHaptic(view)
            }
        }
        s
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sensor) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { sensor.prepare(); currentVm.refreshSensitivity(); currentVm.refreshOutgoing() }
                Lifecycle.Event.ON_PAUSE -> { sensor.release(); currentVm.onHoldEnd() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) sensor.prepare()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensor.release()
        }
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        vm.clearNotice()
    }

    // Through the view model, which runs the copy off the main thread and turns any failure into a notice.
    fun add(block: () -> app.mjolnir.android.share.Imported) = vm.importShared(emptyList(), block)
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) add { importer.importUris(uris) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Reading now still works; only reading after a restart might not.
            }
            add { importer.importTree(uri) }
        }
    }

    val flight = rememberHammerFlight(state.phase)
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var hammerCentre by remember { mutableStateOf<Offset?>(null) } // in window coordinates
    Box(modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.positionInWindow() }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                HammerImage(Modifier.size(32.dp))
                Text(
                    "MJOLNIR", Modifier.padding(start = 10.dp).weight(1f),
                    style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 0.5.sp),
                )
                IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                    Text("📄", Modifier.semantics { contentDescription = "Add files" })
                }
                IconButton(onClick = { pickFolder.launch(null) }) {
                    Text("📁", Modifier.semantics { contentDescription = "Add folder" })
                }
            }
            Box(Modifier.padding(horizontal = 20.dp)) { TargetPicker(state, vm, onPairDevice) }
            val throwing = state.phase == ThrowPhase.Throwing
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.tray.isNotEmpty()) {
                    item(key = "tray-header") { SectionLabel("In your tray · ${state.tray.size}") }
                    items(state.tray, key = { it.id }) { item ->
                        TrayRow(item, checked = item.id in state.selected, removable = !throwing,
                            onToggle = { vm.toggle(item.id) }, onRemove = { vm.remove(item.id) })
                    }
                }
                if (state.outgoing.isNotEmpty()) {
                    item(key = "on-the-way") { SectionLabel("On the way", Modifier.padding(top = 8.dp)) }
                    items(state.outgoing, key = { "out-" + it.id }) { item ->
                        OutgoingRow(item, outgoingTarget(state, item), onFinished = { vm.refreshOutgoing() }, onSendNow = {
                            scope.launch(Dispatchers.IO) { sendNow(context, item.id) }
                        })
                    }
                }
            }
            HammerButton(
                vm, sensor, state.phase, hasTarget = state.targetKey != null, flight = flight,
                modifier = Modifier.padding(vertical = 12.dp), onCentre = { hammerCentre = it },
            )
        }
        FlyingHammer(flight, state.phase, start = hammerCentre?.let { it - overlayOrigin }) // above everything, unclipped, so it can leave the top of the screen
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 260.dp))
    }
}

/** An uppercase muted section heading. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, letterSpacing = 1.sp),
        color = Forge.Muted,
    )
}

/** One pending background throw: `{items} to {pc}` and its status; a row waiting for Wi-Fi offers `Send now`. */
@Composable
private fun OutgoingRow(item: OutgoingFiles, toName: String, onFinished: () -> Unit, onSendNow: () -> Unit) {
    val context = LocalContext.current
    val infos by remember(item.id) {
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(uniqueThrowWork(item.id))
    }.collectAsStateWithLifecycle(initialValue = null)
    val status = outgoingStatus(infos.orEmpty())
    val finished = infos?.let { it.isNotEmpty() && throwWorkFinished(it) } == true
    val currentOnFinished by rememberUpdatedState(onFinished)
    var rescheduled by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        currentOnFinished() // sent or given back: re-read outbox and tray
        // Work that failed or was cancelled while the item is still queued would leave the row waiting forever;
        // start it again (once per row, so a worker that keeps failing doesn't loop).
        val stuck = infos.orEmpty().any { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }
        if (stuck && !rescheduled) {
            rescheduled = true
            scheduleOutgoing(context.applicationContext, item.id, policy = ExistingWorkPolicy.KEEP)
        }
    }
    OutgoingCard(rowTitle(item, toName), status, onSendNow)
}

/** A pending throw: its title, a 6 dp progress bar and the status line; a row waiting for Wi-Fi offers `Send now`. */
@Composable
internal fun OutgoingCard(title: String, status: OutgoingStatus, onSendNow: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Forge.Border, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (status.waitingForWifi && !status.running) TextButton(onClick = onSendNow) { Text("Send now") }
        }
        val pct = status.progressPct
        if (status.running && pct == null) {
            // Sending, but no percentage yet: an indeterminate bar. The label below carries the status for TalkBack.
            LinearProgressIndicator(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).clearAndSetSemantics {},
                color = Forge.Accent, trackColor = Forge.Border, strokeCap = StrokeCap.Round, gapSize = 0.dp,
            )
        } else {
            // Queued or waiting for Wi-Fi shows an empty track.
            Box(Modifier.fillMaxWidth().height(6.dp).background(Forge.Border, RoundedCornerShape(3.dp)).clearAndSetSemantics {}) {
                Box(Modifier.fillMaxWidth((pct ?: 0).coerceIn(0, 100) / 100f).height(6.dp).background(Forge.Accent, RoundedCornerShape(3.dp)))
            }
        }
        Text(status.label, style = MaterialTheme.typography.bodySmall, color = Forge.Muted, maxLines = 1)
    }
}

private fun kindLabel(peer: app.mjolnir.protocol.Peer) = when (peer.kind) {
    app.mjolnir.protocol.DeviceKind.PC -> "PC"
    app.mjolnir.protocol.DeviceKind.PHONE -> "Phone"
    app.mjolnir.protocol.DeviceKind.GUEST -> "Guest"
}

@Composable
private fun TargetPicker(state: ThrowUiState, vm: ThrowViewModel, onPairDevice: () -> Unit) {
    if (state.targets.isEmpty()) {
        TargetCard(null, null, onClick = onPairDevice)
        return
    }
    var open by remember { mutableStateOf(false) }
    val current = state.target ?: state.targets.first()
    Box {
        val now = rememberNowEverySecond(state.guestExpiresAtEpochMs != null)
        TargetCard(state.nameOf(current) + (current.space?.let { " · ${it.name}" } ?: ""), kindLabel(current), onClick = { open = true },
            subtitle = if (current.kind == app.mjolnir.protocol.DeviceKind.GUEST) state.guestSubtitle(now) else null)
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            containerColor = Forge.Surface, border = BorderStroke(1.dp, Forge.Border),
        ) {
            for (group in state.groups) {
                group.title?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Forge.Muted) }
                for (device in group.peers) {
                    DropdownMenuItem(
                        text = {
                            val chosen = state.isSelected(device) // every card of the chosen device, under each heading
                            Column {
                                Text(
                                    "${state.nameOf(device)} (${kindLabel(device)})", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (chosen) Forge.Accent else Color.Unspecified, fontWeight = if (chosen) FontWeight.SemiBold else null,
                                )
                                if (device.kind == app.mjolnir.protocol.DeviceKind.GUEST) {
                                    Text(state.guestSubtitle(now), style = MaterialTheme.typography.bodySmall, color = Forge.Muted)
                                }
                            }
                        },
                        onClick = { vm.selectTarget(device.destinationKey); open = false },
                    )
                }
            }
        }
    }
}

/** The target selector: `Throw to`, the device name, a `PC`/`Phone` badge and a chevron. [name] is null before any pairing. */
@Composable
internal fun TargetCard(name: String?, kind: String?, onClick: () -> Unit, subtitle: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(Forge.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, Forge.Border, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (name == null) {
            Text("Pair a device first", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
        } else {
            Text("Throw to", style = MaterialTheme.typography.bodySmall, color = Forge.Muted)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Forge.Muted, maxLines = 1) }
            }
            if (kind != null) {
                Text(
                    kind,
                    Modifier.background(Forge.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = Forge.OnAccent,
                )
            }
            Canvas(Modifier.size(16.dp)) {
                val w = 2.dp.toPx()
                val p = Path().apply { moveTo(size.width * 0.25f, size.height * 0.375f); lineTo(size.width * 0.5f, size.height * 0.625f); lineTo(size.width * 0.75f, size.height * 0.375f) }
                drawPath(p, Forge.Muted, style = Stroke(w))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrayRow(item: TrayItem, checked: Boolean, removable: Boolean, onToggle: () -> Unit, onRemove: () -> Unit) {
    val canRemove by rememberUpdatedState(removable)
    val removeNow by rememberUpdatedState(onRemove)
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value != SwipeToDismissBoxValue.Settled && canRemove) { removeNow(); true } else false
    })
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = removable,
        enableDismissFromEndToStart = removable,
        backgroundContent = {},
    ) {
        val (title, subtitle) = describe(item)
        TrayCard(title, subtitle, checked, onToggle)
    }
}

/** A tray entry: accent checkbox, name (500) and a muted detail line, on a surface card. */
@Composable
internal fun TrayCard(title: String, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(Forge.Surface, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }) // one target for TalkBack
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Forge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private data class RowText(val title: String, val subtitle: String?)

private fun describe(item: TrayItem): RowText = when (item) {
    is TrayItem.Link -> {
        val host = runCatching { java.net.URI(item.tab.url).host }.getOrNull()
        RowText(item.tab.title?.takeIf { it.isNotBlank() } ?: item.tab.url, host)
    }
    is TrayItem.LocalFile -> RowText(item.name, Messages.size(item.sizeBytes))
    is TrayItem.Folder -> {
        val n = item.files.size
        RowText(item.name, "${Messages.items(0, n)}, ${Messages.size(item.files.sumOf { it.sizeBytes })}")
    }
}

/** Who an *On the way* row goes to: the device's shown name, then ` · {space}` for a throw made in a space. */
internal fun outgoingTarget(state: ThrowUiState, item: OutgoingFiles): String {
    val peer = state.targets.find { it.deviceId == item.to && it.space?.id == item.spaceId }
        ?: state.targets.find { it.deviceId == item.to }
    val name = peer?.let(state::nameOf) ?: state.nicknames[item.to] ?: item.toName
    // The live name wins (a renamed space); the throw's own record keeps the label once the space has left the list.
    val spaceName = item.spaceId?.let { id -> state.targets.firstNotNullOfOrNull { it.space?.takeIf { s -> s.id == id } }?.name ?: item.spaceName }
    return if (spaceName == null) name else "$name · $spaceName"
}

/** The device clock, refreshed each second while [ticking] (for a countdown). */
@Composable
private fun rememberNowEverySecond(ticking: Boolean): Long {
    val now by produceState(System.currentTimeMillis(), ticking) {
        while (ticking) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    return now
}
