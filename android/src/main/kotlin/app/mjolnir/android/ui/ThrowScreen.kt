package app.mjolnir.android.ui

import android.net.Uri
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    LaunchedEffect(Unit) { vm.refreshSensitivity() } // entering the screen: Settings may have changed it
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
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TargetPicker(state, vm, onPairDevice) }
                IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                    Text("📄", Modifier.semantics { contentDescription = "Add files" })
                }
                IconButton(onClick = { pickFolder.launch(null) }) {
                    Text("📁", Modifier.semantics { contentDescription = "Add folder" })
                }
            }
            val throwing = state.phase == ThrowPhase.Throwing
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.outgoing.isNotEmpty()) {
                    item(key = "on-the-way") {
                        Text("On the way", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                    }
                    items(state.outgoing, key = { "out-" + it.id }) { item ->
                        OutgoingRow(item, onFinished = { vm.refreshOutgoing() }, onSendNow = {
                            scope.launch(Dispatchers.IO) { sendNow(context, item.id) }
                        })
                    }
                    item(key = "tray-gap") { Spacer(Modifier.height(8.dp)) }
                }
                items(state.tray, key = { it.id }) { item ->
                    TrayRow(item, checked = item.id in state.selected, removable = !throwing,
                        onToggle = { vm.toggle(item.id) }, onRemove = { vm.remove(item.id) })
                }
            }
            HammerButton(
                vm, sensor, state.phase, hasTarget = state.targetId != null, flight = flight,
                modifier = Modifier.padding(vertical = 16.dp).onGloballyPositioned { hammerCentre = it.boundsInWindow().center },
            )
        }
        FlyingHammer(flight, state.phase, start = hammerCentre?.let { it - overlayOrigin }) // above everything, unclipped, so it can leave the top of the screen
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 232.dp))
    }
}

/** One pending background throw: `{items} to {pc}` and its status; a row waiting for Wi-Fi offers `Send now`. */
@Composable
private fun OutgoingRow(item: OutgoingFiles, onFinished: () -> Unit, onSendNow: () -> Unit) {
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
    ListItem(
        leadingContent = { Text("📤") },
        headlineContent = { Text(rowTitle(item), maxLines = 1) },
        supportingContent = { Text(status.label, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = if (status.waitingForWifi && !status.running) ({ TextButton(onClick = onSendNow) { Text("Send now") } }) else null,
    )
}

private fun kindLabel(peer: app.mjolnir.protocol.Peer) = if (peer.kind == app.mjolnir.protocol.DeviceKind.PC) "PC" else "Phone"

@Composable
private fun TargetPicker(state: ThrowUiState, vm: ThrowViewModel, onPairDevice: () -> Unit) {
    if (state.targets.isEmpty()) {
        TextButton(onClick = onPairDevice) { Text("Pair a device first") }
        return
    }
    var open by remember { mutableStateOf(false) }
    val current = state.targets.find { it.deviceId == state.targetId } ?: state.targets.first()
    Box {
        OutlinedButton(onClick = { open = true }) { Text("To: ${current.name} (${kindLabel(current)}) ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (device in state.targets) {
                DropdownMenuItem(text = { Text("${device.name} (${kindLabel(device)})") }, onClick = { vm.selectTarget(device.deviceId); open = false })
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
        val (title, subtitle, icon) = describe(item)
        ListItem(
            modifier = Modifier.clickable(onClick = onToggle),
            leadingContent = { Text(icon) },
            headlineContent = { Text(title, maxLines = 1) },
            supportingContent = subtitle?.let { { Text(it, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            trailingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle() }) },
        )
    }
}

private data class RowText(val title: String, val subtitle: String?, val icon: String)

private fun describe(item: TrayItem): RowText = when (item) {
    is TrayItem.Link -> {
        val host = runCatching { java.net.URI(item.tab.url).host }.getOrNull()
        RowText(item.tab.title?.takeIf { it.isNotBlank() } ?: item.tab.url, host, "🔗")
    }
    is TrayItem.LocalFile -> RowText(item.name, Messages.size(item.sizeBytes), "📄")
    is TrayItem.Folder -> {
        val n = item.files.size
        RowText(item.name, "${Messages.items(0, n)}, ${Messages.size(item.files.sumOf { it.sizeBytes })}", "📁")
    }
}
