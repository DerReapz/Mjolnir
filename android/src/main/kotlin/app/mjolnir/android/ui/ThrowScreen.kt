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
import app.mjolnir.android.data.TrayItem
import app.mjolnir.android.share.UriImporter
import app.mjolnir.android.throwing.FlickSensor
import app.mjolnir.android.throwing.ThrowDetector
import app.mjolnir.client.Messages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThrowScreen(
    vm: ThrowViewModel,
    importer: UriImporter,
    onPairPc: () -> Unit,
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
                Lifecycle.Event.ON_RESUME -> { sensor.prepare(); currentVm.refreshSensitivity() }
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

    fun add(block: () -> app.mjolnir.android.share.Imported) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            vm.addShared(r.items, r.notices)
        }
    }
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
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PcPicker(state, vm, onPairPc) }
                IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                    Text("📄", Modifier.semantics { contentDescription = "Add files" })
                }
                IconButton(onClick = { pickFolder.launch(null) }) {
                    Text("📁", Modifier.semantics { contentDescription = "Add folder" })
                }
            }
            val throwing = state.phase == ThrowPhase.Throwing
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.tray, key = { it.id }) { item ->
                    TrayRow(item, checked = item.id in state.selected, removable = !throwing,
                        onToggle = { vm.toggle(item.id) }, onRemove = { vm.remove(item.id) })
                }
            }
            HammerButton(vm, sensor, state.phase, hasTarget = state.targetPcId != null, flight = flight, modifier = Modifier.padding(vertical = 16.dp))
        }
        FlyingHammer(flight, state.phase) // above everything, unclipped, so it can leave the top of the screen
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 232.dp))
    }
}

@Composable
private fun PcPicker(state: ThrowUiState, vm: ThrowViewModel, onPairPc: () -> Unit) {
    if (state.pcs.isEmpty()) {
        TextButton(onClick = onPairPc) { Text("Pair a PC first") }
        return
    }
    var open by remember { mutableStateOf(false) }
    val current = state.pcs.find { it.deviceId == state.targetPcId } ?: state.pcs.first()
    Box {
        OutlinedButton(onClick = { open = true }) { Text("To: ${current.name} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (pc in state.pcs) {
                DropdownMenuItem(text = { Text(pc.name) }, onClick = { vm.selectTarget(pc.deviceId); open = false })
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
