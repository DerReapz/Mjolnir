package app.mjolnir.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import javax.imageio.ImageIO
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Data
import org.jetbrains.skia.Typeface as SkTypeface

/** "Forge" design tokens: dark charcoal with molten amber. */
object Forge {
    val background = Color(0xFF141414)
    val surface = Color(0xFF1F1F1F)
    val strip = Color(0xFF1A1A1A)
    val panel = Color(0xFF171717)
    val border = Color(0xFF2E2E2E)
    val dashed = Color(0xFF4A4A4A)
    val text = Color(0xFFF5F2EC)
    val muted = Color(0xFFA8A29E)
    val accent = Color(0xFFF5A524)
    val accentHover = Color(0xFFFFC35C)
    val onAccent = Color(0xFF141414)
    val accentGlow = Color(0xFFF5A524).copy(alpha = 0.13f)
    val error = Color(0xFFFF8A80)

    val cardShape = RoundedCornerShape(12.dp)
    val inputShape = RoundedCornerShape(10.dp)
    val buttonShape = RoundedCornerShape(8.dp)
    val rowShape = RoundedCornerShape(8.dp)
}

private fun loadVariable(resource: String, weight: Int, alias: String): FontFamily {
    val bytes = Forge::class.java.getResourceAsStream(resource)!!.use { it.readBytes() }
    val base = FontMgr.default.makeFromData(Data.makeFromBytes(bytes))!!
    val tf: SkTypeface = base.makeClone(FontVariation("wght", weight.toFloat()))
    return FontFamily(Typeface(tf, alias))
}

/** Space Grotesk (headings, buttons) and Inter (body), OFL, bundled as variable TTFs; one family per used weight. */
object ForgeFonts {
    val grotesk500: FontFamily by lazy { loadVariable("/fonts/SpaceGrotesk.ttf", 500, "SpaceGrotesk500") }
    val grotesk700: FontFamily by lazy { loadVariable("/fonts/SpaceGrotesk.ttf", 700, "SpaceGrotesk700") }
    val inter400: FontFamily by lazy { loadVariable("/fonts/Inter.ttf", 400, "Inter400") }
    val inter500: FontFamily by lazy { loadVariable("/fonts/Inter.ttf", 500, "Inter500") }
    val inter600: FontFamily by lazy { loadVariable("/fonts/Inter.ttf", 600, "Inter600") }
}

private fun style(family: FontFamily, size: Int, spacing: Double = 0.0) =
    TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = size.sp, letterSpacing = spacing.sp)

private fun forgeTypography() = Typography(
    displayLarge = style(ForgeFonts.grotesk700, 48), displayMedium = style(ForgeFonts.grotesk700, 40),
    displaySmall = style(ForgeFonts.grotesk700, 32),
    headlineLarge = style(ForgeFonts.grotesk700, 28), headlineMedium = style(ForgeFonts.grotesk700, 24),
    headlineSmall = style(ForgeFonts.grotesk700, 20),
    titleLarge = style(ForgeFonts.grotesk700, 20), titleMedium = style(ForgeFonts.grotesk700, 16),
    titleSmall = style(ForgeFonts.grotesk500, 14),
    bodyLarge = style(ForgeFonts.inter400, 15), bodyMedium = style(ForgeFonts.inter400, 14),
    bodySmall = style(ForgeFonts.inter400, 12),
    labelLarge = style(ForgeFonts.inter500, 14), labelMedium = style(ForgeFonts.inter500, 12),
    labelSmall = style(ForgeFonts.inter500, 11),
)

private fun forgeColors(): ColorScheme = darkColorScheme(
    primary = Forge.accent, onPrimary = Forge.onAccent,
    primaryContainer = Forge.accentGlow, onPrimaryContainer = Forge.text,
    secondary = Forge.accent, onSecondary = Forge.onAccent,
    background = Forge.background, onBackground = Forge.text,
    surface = Forge.surface, onSurface = Forge.text,
    surfaceVariant = Forge.surface, onSurfaceVariant = Forge.muted,
    surfaceContainer = Forge.surface, surfaceContainerHigh = Forge.surface, surfaceContainerHighest = Forge.surface,
    outline = Forge.border, outlineVariant = Forge.border,
    error = Forge.error, onError = Forge.onAccent,
)

@Composable
fun MjolnirTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = forgeColors(), typography = forgeTypography()) {
        Surface(color = Forge.background, contentColor = Forge.text) { content() }
    }
}

@Composable
fun forgeHammer(): BitmapPainter = remember {
    val image = Forge::class.java.getResourceAsStream("/hammer-white.png")!!.use { ImageIO.read(it) }
    BitmapPainter(image.toComposeImageBitmap())
}

/** Title strip with the white hammer, as in the mockup. */
@Composable
fun ForgeHeader(title: String) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Forge.strip).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(forgeHammer(), contentDescription = null, modifier = Modifier.size(20.dp))
        Text(title, style = style(ForgeFonts.grotesk700, 15, 0.5))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Forge.border))
}

/** Space Grotesk 700 label used inside buttons. */
@Composable
private fun ButtonLabel(text: String, size: Int = 14) = Text(text, style = style(ForgeFonts.grotesk700, size, 0.2).copy(color = Color.Unspecified))

@Composable
fun ForgePrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, big: Boolean = false) {
    Button(
        onClick = onClick, enabled = enabled, shape = if (big) Forge.inputShape else Forge.buttonShape,
        modifier = modifier.height(if (big) 48.dp else 40.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Forge.accent, contentColor = Forge.onAccent,
            disabledContainerColor = Forge.accent.copy(alpha = 0.3f), disabledContentColor = Forge.onAccent.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) { ButtonLabel(text, if (big) 16 else 14) }
}

/** The disabled state of the big accent button: legible muted text on a surface with a border, not a dimmed accent. */
@Composable
fun ForgeDisabledButton(text: String, modifier: Modifier = Modifier, big: Boolean = true) {
    Button(
        onClick = {}, enabled = false, shape = if (big) Forge.inputShape else Forge.buttonShape,
        modifier = modifier.height(if (big) 48.dp else 40.dp),
        border = BorderStroke(1.dp, Forge.border),
        colors = ButtonDefaults.buttonColors(disabledContainerColor = Forge.surface, disabledContentColor = Forge.muted),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) { ButtonLabel(text, if (big) 16 else 14) }
}

@Composable
fun ForgeSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = Forge.buttonShape, modifier = modifier.height(40.dp),
        border = BorderStroke(1.dp, Forge.border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Forge.surface, contentColor = Forge.text, disabledContentColor = Forge.muted),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) { Text(text, style = style(ForgeFonts.inter500, 14).copy(color = Color.Unspecified)) }
}

@Composable
fun ForgeTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, muted: Boolean = false) {
    TextButton(
        onClick = onClick, modifier = modifier.height(40.dp), shape = Forge.buttonShape,
        colors = ButtonDefaults.textButtonColors(contentColor = if (muted) Forge.muted else Forge.accent),
    ) { Text(text, style = style(ForgeFonts.inter500, 14).copy(color = Color.Unspecified)) }
}

@Composable
fun forgeCheckboxColors(): CheckboxColors = CheckboxDefaults.colors(
    checkedColor = Forge.accent, checkmarkColor = Forge.onAccent, uncheckedColor = Forge.muted,
)

@Composable
fun forgeFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Forge.accent, unfocusedBorderColor = Forge.border,
    focusedContainerColor = Forge.surface, unfocusedContainerColor = Forge.surface,
    focusedTextColor = Forge.text, unfocusedTextColor = Forge.text, cursorColor = Forge.accent,
    focusedLabelColor = Forge.muted, unfocusedLabelColor = Forge.muted,
    focusedPlaceholderColor = Forge.muted, unfocusedPlaceholderColor = Forge.muted,
)

/** Section label: 12px uppercase muted, letter-spacing 1. */
@Composable
fun ForgeSectionLabel(text: String) =
    Text(text.uppercase(), style = style(ForgeFonts.inter600, 12, 1.0).copy(color = Forge.muted))

@Composable
fun Modifier.forgeRow(): Modifier = this.background(Forge.surface, Forge.rowShape)

/** Device / selectable card: 2px accent border when selected, else 1px border. */
@Composable
fun Modifier.forgeCard(selected: Boolean): Modifier =
    this.background(Forge.surface, Forge.inputShape)
        .border(if (selected) 2.dp else 1.dp, if (selected) Forge.accent else Forge.border, Forge.inputShape)

@Composable
fun ForgeBodyText(text: String, muted: Boolean = false, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE,
                  overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip) =
    Text(text, modifier, maxLines = maxLines, overflow = overflow,
        style = style(ForgeFonts.inter400, 14).copy(color = if (muted) Forge.muted else Forge.text))

/** Big pairing code in Space Grotesk 700. */
@Composable
fun ForgeCode(text: String) =
    Text(text, style = style(ForgeFonts.grotesk700, 48, 6.0).copy(color = Forge.accent))

/** Single-line text input at the mockup's 40 px: dark field, 1 px border (accent while focused, red when [isError]), radius 10. */
@Composable
fun ForgeInput(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", isError: Boolean = false, onEnter: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val border = when { isError -> Forge.error; focused -> Forge.accent; else -> Forge.border }
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = style(ForgeFonts.inter400, 14).copy(color = Forge.text),
        cursorBrush = SolidColor(Forge.accent),
        modifier = modifier.onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { e ->
            if (onEnter != null && e.type == KeyEventType.KeyDown && e.key == Key.Enter) { onEnter(); true } else false
        },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).background(Forge.panel, Forge.inputShape)
                    .border(1.dp, border, Forge.inputShape).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style(ForgeFonts.inter400, 14).copy(color = Forge.muted), maxLines = 1)
                inner()
            }
        },
    )
}

/** A dialog drawn over its tab: dimmed scrim (click dismisses) and a centred card. */
@Composable
fun ForgeModal(onDismiss: () -> Unit, maxWidth: Dp = 400.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = maxWidth).fillMaxWidth().forgeCard(selected = false)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}) // clicks inside do not dismiss
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}
