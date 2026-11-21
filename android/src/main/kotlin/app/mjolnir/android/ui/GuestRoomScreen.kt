package app.mjolnir.android.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.client.Exchanged
import app.mjolnir.client.GuestRoomUi
import app.mjolnir.client.formatCountdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val GuestGreen = androidx.compose.ui.graphics.Color(0xFF4ADE80)

/** The system share sheet for the invite link (text only; there is no QR code on the phone). */
fun shareLinkIntent(link: String): Intent =
    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null)

/** The Guest room screen, opened from Devices. */
@Composable
fun GuestRoomScreen(vm: GuestRoomViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.state.collectAsState()
    val exchanged by vm.exchanged.collectAsState()
    LaunchedEffect(Unit) { vm.restore() }
    GuestRoomContent(ui, exchanged, vm::open, vm::end, onBack, modifier)
}

@Composable
fun GuestRoomContent(
    ui: GuestRoomUi, exchanged: List<Exchanged>, onOpen: () -> Unit, onEnd: () -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier, clock: () -> Long = System::currentTimeMillis,
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Guest room", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        }
        val open = ui as? GuestRoomUi.Open
        if (open == null) {
            Notes()
            (ui as? GuestRoomUi.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onOpen, enabled = ui !is GuestRoomUi.Opening, shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open a guest room") }
        } else {
            OpenRoom(open, exchanged, onEnd, clock)
        }
    }
}

@Composable
private fun Notes() {
    Text("Works in any browser — nothing to install.", color = Forge.Muted, style = MaterialTheme.typography.bodySmall)
    Text("Only this phone can exchange with the guest.", color = Forge.Muted, style = MaterialTheme.typography.bodySmall)
    Text("Unclaimed files are deleted when the room closes.", color = Forge.Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.OpenRoom(room: GuestRoomUi.Open, exchanged: List<Exchanged>, onEnd: () -> Unit, clock: () -> Long) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val left by produceState(formatCountdown(room.expiresAtEpochMs - clock()), room.expiresAtEpochMs) {
        while (true) {
            value = formatCountdown(room.expiresAtEpochMs - clock())
            delay(1000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (room.guest != null) {
            Text(
                "Guest connected", color = Forge.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(GuestGreen, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Text("Closes in $left", Modifier.weight(1f), color = Forge.Muted, style = MaterialTheme.typography.bodyMedium)
    }
    room.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Column(
        Modifier.fillMaxWidth().background(Forge.Surface, RoundedCornerShape(12.dp)).border(1.dp, Forge.Border, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Invite link", color = Forge.Muted, style = MaterialTheme.typography.labelMedium)
        Text(room.link, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("Guest room link", room.link).toClipEntry()) } },
                shape = ButtonShape, modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Copy link") }
            OutlinedButton(onClick = { context.startActivity(shareLinkIntent(room.link)) }, shape = ButtonShape, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share…") }
        }
        Text("Works in any browser — nothing to install.", color = Forge.Muted, style = MaterialTheme.typography.bodySmall)
    }
    Text("Exchanged", color = Forge.Muted, style = MaterialTheme.typography.labelMedium)
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(exchanged, key = { it.id }) { ExchangedRow(it) }
    }
    Text("Only this phone can exchange with the guest.", color = Forge.Muted, style = MaterialTheme.typography.bodySmall)
    Text("Unclaimed files are deleted when the room closes.", color = Forge.Muted, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onEnd, shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("End room") }
}

@Composable
private fun ExchangedRow(item: Exchanged) {
    ForgeCard {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val color = if (item.fromGuest) Forge.Accent else Forge.Muted
            Text(
                if (item.fromGuest) "FROM GUEST" else "YOU SENT", color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.border(1.dp, color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = if (item.downloadedByGuest) listOf(item.detail, "downloaded by guest").filter { it.isNotEmpty() }.joinToString(" · ") else item.detail
                Text(detail, color = Forge.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
