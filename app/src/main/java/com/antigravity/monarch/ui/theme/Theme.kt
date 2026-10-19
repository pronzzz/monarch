package com.antigravity.monarch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.antigravity.monarch.domain.model.StatType

// "System window" palette: deep void background, glowing cyan rim, gold for the player.
object MonarchColors {
    val Void = Color(0xFF05060D)
    val Panel = Color(0xFF0B1020)
    val PanelHigh = Color(0xFF121A30)
    val Rim = Color(0xFF00E5FF)
    val RimDim = Color(0x5500E5FF)
    val Accent = Color(0xFF3D7BFF)
    val Gold = Color(0xFFFFC940)
    val Text = Color(0xFFE6F1FF)
    val TextDim = Color(0xFF7C8BA5)
    val Track = Color(0xFF1A2236)
    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFB454)
    val Danger = Color(0xFFFF5370)
}

fun statColor(stat: StatType): Color = when (stat) {
    StatType.STRENGTH -> Color(0xFFFF5370)
    StatType.AGILITY -> Color(0xFF3DDC97)
    StatType.ENDURANCE -> Color(0xFF4FC3F7)
    StatType.HEALTH -> Color(0xFFFFB454)
    StatType.INTELLIGENCE -> Color(0xFFB388FF)
}

private val Scheme = darkColorScheme(
    primary = MonarchColors.Rim,
    onPrimary = MonarchColors.Void,
    secondary = MonarchColors.Gold,
    onSecondary = MonarchColors.Void,
    tertiary = MonarchColors.Accent,
    background = MonarchColors.Void,
    onBackground = MonarchColors.Text,
    surface = MonarchColors.Panel,
    onSurface = MonarchColors.Text,
    surfaceVariant = MonarchColors.PanelHigh,
    onSurfaceVariant = MonarchColors.TextDim,
    surfaceContainer = MonarchColors.Panel,
    surfaceContainerHigh = MonarchColors.PanelHigh,
    outline = MonarchColors.RimDim,
    error = MonarchColors.Danger,
)

private val Mono = FontFamily.Monospace

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 40.sp, letterSpacing = 1.sp),
    headlineSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp),
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
)

@Composable
fun MonarchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
