package app.mjolnir.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mjolnir.R

/** The Forge design tokens: dark charcoal with molten amber. */
object Forge {
    val Background = Color(0xFF141414)
    val Surface = Color(0xFF1F1F1F)
    val SurfaceAlt = Color(0xFF1A1A1A)
    val Border = Color(0xFF2E2E2E)
    val Text = Color(0xFFF5F2EC)
    val Muted = Color(0xFFA8A29E)
    val Accent = Color(0xFFF5A524)
    val AccentPressed = Color(0xFFFFC35C)
    val OnAccent = Color(0xFF141414)
    val AccentGlow = Color(0x21F5A524) // 13% alpha
    val Error = Color(0xFFFF8A80)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Headings, buttons, wordmark. */
val SpaceGrotesk = FontFamily(variable(R.font.space_grotesk, 500), variable(R.font.space_grotesk, 700))

/** Body text. */
val Inter = FontFamily(variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600))

private val forgeColors: ColorScheme = darkColorScheme(
    primary = Forge.Accent,
    onPrimary = Forge.OnAccent,
    primaryContainer = Forge.Accent,
    onPrimaryContainer = Forge.OnAccent,
    secondary = Forge.Accent,
    onSecondary = Forge.OnAccent,
    background = Forge.Background,
    onBackground = Forge.Text,
    surface = Forge.Background,
    onSurface = Forge.Text,
    surfaceVariant = Forge.Surface,
    onSurfaceVariant = Forge.Muted,
    surfaceContainer = Forge.Background,
    surfaceContainerHigh = Forge.Surface,
    surfaceContainerHighest = Forge.Surface,
    surfaceContainerLow = Forge.Surface,
    surfaceContainerLowest = Forge.Background,
    outline = Forge.Muted,
    outlineVariant = Forge.Border,
    error = Forge.Error,
    onError = Forge.OnAccent,
    inverseSurface = Forge.Text,
    inverseOnSurface = Forge.Background,
    inversePrimary = Forge.Accent,
    scrim = Color.Black,
)

private fun body(size: Int, weight: Int = 400) = TextStyle(fontFamily = Inter, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = (size * 1.4f).sp)
private fun heading(size: Int, weight: Int) = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = (size * 1.3f).sp)

private val forgeTypography = Typography(
    displayLarge = heading(48, 700), displayMedium = heading(40, 700), displaySmall = heading(32, 700),
    headlineLarge = heading(28, 700), headlineMedium = heading(24, 700), headlineSmall = heading(22, 700),
    titleLarge = heading(20, 700), titleMedium = heading(16, 500), titleSmall = body(14, 600),
    bodyLarge = body(16), bodyMedium = body(14), bodySmall = body(12),
    labelLarge = heading(14, 500), labelMedium = body(12, 600), labelSmall = body(11, 600),
)

private val forgeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

@Composable
fun MjolnirTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = forgeColors, typography = forgeTypography, shapes = forgeShapes, content = content)
}

/** Buttons use 10 dp corners, text fields 14 dp (cards are 12). */
val ButtonShape = RoundedCornerShape(10.dp)
val FieldShape = RoundedCornerShape(14.dp)

/** Text field colours: a surface container with a border outline that turns accent while focused. */
@Composable
fun forgeFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Forge.Surface, unfocusedContainerColor = Forge.Surface,
    focusedBorderColor = Forge.Accent, unfocusedBorderColor = Forge.Border,
    cursorColor = Forge.Accent,
)

/** A surface card, 12 dp radius, at least 56 dp tall. */
@Composable
fun ForgeCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(Forge.Surface, shape)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
