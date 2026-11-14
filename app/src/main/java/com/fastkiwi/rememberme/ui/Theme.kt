package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastkiwi.rememberme.R

/** Noto Sans Arabic (SIL OFL 1.1), bundled so every phone shows the same face. It also has Latin letters. */
val AppFont = FontFamily(
    Font(R.font.noto_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.noto_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.noto_sans_arabic_bold, FontWeight.Bold),
)

// DESIGN-SPEC §2.2: letter spacing 0 (it breaks Arabic joining) and no line trimming, so dots never clip.
private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = AppFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

val AppTypography = Typography(
    displayLarge = style(57, 72, FontWeight.Medium).copy(fontFeatureSettings = "tnum"),
    displayMedium = style(45, 56, FontWeight.Medium),
    displaySmall = style(36, 48, FontWeight.Medium),
    headlineLarge = style(32, 44, FontWeight.Bold),
    headlineMedium = style(28, 40, FontWeight.Bold),
    headlineSmall = style(24, 36, FontWeight.Bold),
    titleLarge = style(22, 32, FontWeight.Bold),
    titleMedium = style(17, 26, FontWeight.Medium),
    titleSmall = style(15, 22, FontWeight.Bold),
    bodyLarge = style(16, 28, FontWeight.Normal),
    bodyMedium = style(14, 24, FontWeight.Normal),
    bodySmall = style(13, 20, FontWeight.Normal),
    labelLarge = style(15, 22, FontWeight.Medium),
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
)

/** The time on each reminder row: the row's hero (tabular digits so a column of times lines up). */
val RowTime = style(22, 30, FontWeight.Medium).copy(fontFeatureSettings = "tnum")

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp),
)

object Dimens {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val fabClearance = 88.dp
    val rowMin = 72.dp
    val touch = 48.dp
    val groupOuter = 20.dp
    val groupInner = 4.dp
}

// DESIGN-SPEC §2.1: a complete Material 3 scheme generated from the Windows icon's green (#2F6249), with
// the lavender bell (#C5B3FF) as the tertiary hue. Every role is filled, so no baseline purple shows.
// Contrast report: design/tools/contrast-report.md (all text pairs >= 4.5:1, boundaries >= 3:1).
private val Light = lightColorScheme(
    primary = Color(0xFF246A4B), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFABF2CA), onPrimaryContainer = Color(0xFF005235), inversePrimary = Color(0xFF8FD5AF),
    secondary = Color(0xFF4D6356), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E8D8), onSecondaryContainer = Color(0xFF364B3F),
    tertiary = Color(0xFF645498), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8DEFF), onTertiaryContainer = Color(0xFF4B3C7E),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF7FAF5), onBackground = Color(0xFF181D1A),
    surface = Color(0xFFF7FAF5), onSurface = Color(0xFF181D1A),
    surfaceVariant = Color(0xFFDCE5DD), onSurfaceVariant = Color(0xFF404943), surfaceTint = Color(0xFF246A4B),
    inverseSurface = Color(0xFF2D312E), inverseOnSurface = Color(0xFFEEF2EC),
    outline = Color(0xFF707972), outlineVariant = Color(0xFFC0C9C1), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7FAF5), surfaceDim = Color(0xFFD7DBD6),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F5EF),
    surfaceContainer = Color(0xFFEBEFEA), surfaceContainerHigh = Color(0xFFE5E9E4),
    surfaceContainerHighest = Color(0xFFE0E3DE),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FD5AF), onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF005235), onPrimaryContainer = Color(0xFFABF2CA), inversePrimary = Color(0xFF246A4B),
    secondary = Color(0xFFB4CCBC), onSecondary = Color(0xFF203529),
    secondaryContainer = Color(0xFF364B3F), onSecondaryContainer = Color(0xFFD0E8D8),
    tertiary = Color(0xFFCDBDFF), onTertiary = Color(0xFF352466),
    tertiaryContainer = Color(0xFF4B3C7E), onTertiaryContainer = Color(0xFFE8DEFF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101412), onBackground = Color(0xFFE0E3DE),
    surface = Color(0xFF101412), onSurface = Color(0xFFE0E3DE),
    surfaceVariant = Color(0xFF404943), onSurfaceVariant = Color(0xFFC0C9C1), surfaceTint = Color(0xFF8FD5AF),
    inverseSurface = Color(0xFFE0E3DE), inverseOnSurface = Color(0xFF2D312E),
    outline = Color(0xFF8A938C), outlineVariant = Color(0xFF404943), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF363A37), surfaceDim = Color(0xFF101412),
    surfaceContainerLowest = Color(0xFF0B0F0C), surfaceContainerLow = Color(0xFF181D1A),
    surfaceContainer = Color(0xFF1C211E), surfaceContainerHigh = Color(0xFF272B28),
    surfaceContainerHighest = Color(0xFF313632),
)

/** True when the system's "Remove animations" is on (DESIGN-SPEC §2.5). */
val LocalReducedMotion = compositionLocalOf { false }

@Composable
fun AppTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, shapes = AppShapes, content = content)
}
