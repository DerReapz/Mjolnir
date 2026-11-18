package app.mjolnir.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

enum class Screen(val label: String, val glyph: String) {
    THROW("Throw", "🔨"),
    CAUGHT("Caught", "📥"),
    DEVICES("Devices", "📱"),
    SETTINGS("Settings", "⚙"),
}

/** Bottom navigation over [Screen]; the current screen is held by the caller (in `rememberSaveable`). */
@Composable
fun MjolnirNav(current: Screen, onSelect: (Screen) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier) {
        for (screen in Screen.entries) {
            NavigationBarItem(
                selected = screen == current,
                onClick = { onSelect(screen) },
                icon = { Text(screen.glyph) },
                label = { Text(screen.label) },
            )
        }
    }
}

/** Stand-in until the receive task adds the real Caught screen. */
@Composable
fun CaughtPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing caught yet") }
}
