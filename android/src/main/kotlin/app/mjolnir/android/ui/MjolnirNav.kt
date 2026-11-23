package app.mjolnir.android.ui

import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import app.mjolnir.android.update.UpdateBannerUi
import app.mjolnir.client.Messages
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A 24-unit stroke icon from SVG path data (the Forge nav icons). */
internal fun strokeIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 22.dp, 22.dp, 24f, 24f).apply {
        for (d in paths) {
            addPath(
                addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Miter,
            )
        }
    }.build()

private val ThrowIcon = strokeIcon("throw", "M4 20l8-8M14 4l6 6-4 4-6-6z")
private val CaughtIcon = strokeIcon("caught", "M12 4v12M6 10l6 6 6-6M4 20h16")
private val ChatsIcon = strokeIcon("chats", "M21 12a8 8 0 0 1-11.5 7.2L4 21l1.8-5.5A8 8 0 1 1 21 12z")
private val DevicesIcon = strokeIcon("devices", "M3 5h12v9H3z", "M16 8h5v11h-5z")
private val SettingsIcon = strokeIcon("settings", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M12 2v3M12 19v3M2 12h3M19 12h3")

enum class Screen(val label: String, val icon: ImageVector) {
    THROW("Throw", ThrowIcon),
    CHATS("Chats", ChatsIcon),
    CAUGHT("Caught", CaughtIcon),
    DEVICES("Devices", DevicesIcon),
    SETTINGS("Settings", SettingsIcon),
}

/** Bottom navigation over [Screen]; the current screen is held by the caller (in `rememberSaveable`). */
@Composable
fun MjolnirNav(current: Screen, onSelect: (Screen) -> Unit, modifier: Modifier = Modifier, chatsBadge: Int = 0) {
    NavigationBar(
        modifier.drawBehind { drawLine(Forge.Border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
        containerColor = Forge.Background,
        tonalElevation = 0.dp,
    ) {
        for (screen in Screen.entries) {
            val active = screen == current
            NavigationBarItem(
                selected = active,
                onClick = { onSelect(screen) },
                icon = {
                    if (screen == Screen.CHATS && chatsBadge > 0) {
                        BadgedBox(badge = { Badge(containerColor = Forge.Accent, contentColor = Forge.OnAccent) { Text(if (chatsBadge > 99) "99+" else chatsBadge.toString()) } }) {
                            Icon(screen.icon, contentDescription = null)
                        }
                    } else Icon(screen.icon, contentDescription = null)
                },
                label = { Text(screen.label, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Forge.Accent, selectedTextColor = Forge.Accent,
                    unselectedIconColor = Forge.Muted, unselectedTextColor = Forge.Muted,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}

/**
 * The strip above the screens: an app update (with *Install*) or a relay too old for this app. Nothing when [banner] is
 * null; it pads for the status bar itself (it is the Scaffold's top bar).
 */
@Composable
fun UpdateBanner(banner: UpdateBannerUi?, onInstall: () -> Unit) {
    banner ?: return
    Row(
        Modifier.fillMaxWidth().background(Forge.Surface)
            .drawBehind { drawLine(Forge.Border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(banner.text, style = MaterialTheme.typography.bodyMedium, color = Forge.Text, modifier = Modifier.weight(1f))
        if (banner.install) Button(onClick = onInstall, shape = ButtonShape) { Text(Messages.INSTALL) }
    }
}
