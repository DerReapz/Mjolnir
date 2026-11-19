package app.mjolnir.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.android.data.CaughtItem
import app.mjolnir.android.send.OutgoingStatus
import app.mjolnir.android.throwing.Sensitivity
import app.mjolnir.protocol.Tab

@Composable
private fun Forged(content: @Composable () -> Unit) = MjolnirTheme {
    Column(Modifier.fillMaxSize().background(Forge.Background)) { content() }
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun ThrowPreview() = Forged {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MJOLNIR", style = MaterialTheme.typography.headlineSmall)
        TargetCard("DESKTOP-4F2K", "PC", onClick = {})
        SectionLabel("In your tray · 2", Modifier.padding(top = 10.dp))
        TrayCard("Ragnarök — Wikipedia", "en.wikipedia.org", checked = true, onToggle = {})
        TrayCard("report.pdf", "File · 2.4 MB", checked = false, onToggle = {})
        SectionLabel("On the way", Modifier.padding(top = 8.dp))
        OutgoingCard("12 files to Laptop", OutgoingStatus(running = true, progressPct = 45, currentName = "IMG_2041.jpg", waitingForWifi = false), onSendNow = {})
    }
    HammerDisc("Hold & flick to throw", enabled = true, armed = false, restAlpha = 1f, Modifier.padding(top = 16.dp))
}

@Preview(widthDp = 390, heightDp = 600, showBackground = true)
@Composable
private fun CaughtPreview() = Forged {
    val now = System.currentTimeMillis()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CaughtRow(CaughtItem(tab = Tab("https://example.com", "Example"), fromName = "DESKTOP-4F2K", caughtAtEpochMs = now - 120_000), now) {}
    }
}

@Preview(widthDp = 390, heightDp = 600, showBackground = true)
@Composable
private fun DevicesPreview() = Forged {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ForgeCard {
            Column(Modifier.weight(1f)) {
                Text("DESKTOP-4F2K", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                Text("PC", style = MaterialTheme.typography.bodySmall, color = Forge.Muted)
            }
            TextButton(onClick = {}) { Text("Unpair") }
        }
        Button(onClick = {}, shape = ButtonShape) { Text("Pair a device") }
        Text("4F2K-91", color = Forge.Accent, fontSize = 48.sp, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

@Preview(widthDp = 390, heightDp = 600, showBackground = true)
@Composable
private fun SettingsPreview() = Forged {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = "https://relay.example.com", onValueChange = {}, shape = FieldShape, label = { Text("Relay URL") }, singleLine = true)
        SectionLabel("Flick sensitivity")
        Choices(listOf(Sensitivity.LOW to "Low", Sensitivity.MEDIUM to "Medium", Sensitivity.HIGH to "High"), Sensitivity.MEDIUM) {}
    }
}
