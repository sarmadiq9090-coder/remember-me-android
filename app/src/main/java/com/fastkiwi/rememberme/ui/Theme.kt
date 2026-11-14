package com.fastkiwi.rememberme.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.fastkiwi.rememberme.R

/** Noto Sans Arabic (SIL OFL 1.1), bundled so every phone shows the same face. It also has Latin letters. */
val AppFont = FontFamily(
    Font(R.font.noto_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.noto_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.noto_sans_arabic_bold, FontWeight.Bold),
)

// Letter spacing 0 (it breaks Arabic joining) and no line trimming, so dots never clip.
private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = AppFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

val AppTypography = Typography(
    displaySmall = style(34, 46, FontWeight.Bold),
    headlineLarge = style(30, 42, FontWeight.Bold),
    headlineMedium = style(26, 38, FontWeight.Bold),
    headlineSmall = style(22, 34, FontWeight.Bold),
    titleLarge = style(20, 30, FontWeight.Bold),
    titleMedium = style(17, 26, FontWeight.Medium),
    titleSmall = style(15, 24, FontWeight.Medium),
    bodyLarge = style(17, 28, FontWeight.Normal),
    bodyMedium = style(15, 24, FontWeight.Normal),
    bodySmall = style(13, 20, FontWeight.Normal),
    labelLarge = style(15, 22, FontWeight.Medium),
    labelMedium = style(13, 20, FontWeight.Medium),
    labelSmall = style(12, 18, FontWeight.Medium),
)

// Brand colours from the Windows icon: forest green, lavender, lime. Text pairs meet WCAG AA (>= 4.5:1).
private val Light = lightColorScheme(
    primary = Color(0xFF2F6249), onPrimary = Color.White,
    primaryContainer = Color(0xFFC9EBD5), onPrimaryContainer = Color(0xFF0B2A1C),
    secondary = Color(0xFF5B4B8A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DEFF), onSecondaryContainer = Color(0xFF1C1340),
    tertiary = Color(0xFF3C5A00), onTertiary = Color.White,
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F9F5), onBackground = Color(0xFF191C1A),
    surface = Color(0xFFF7F9F5), onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDDE5DD), onSurfaceVariant = Color(0xFF404943),
    surfaceContainer = Color(0xFFECEFEA), surfaceContainerHigh = Color(0xFFE6E9E4),
    outline = Color(0xFF6F7972),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DD5B3), onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF2F6249), onPrimaryContainer = Color(0xFFE2F5E8),
    secondary = Color(0xFFC5B3FF), onSecondary = Color(0xFF2B1F59),
    secondaryContainer = Color(0xFF433771), onSecondaryContainer = Color(0xFFE6DEFF),
    tertiary = Color(0xFFB9F34A), onTertiary = Color(0xFF1F3700),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0F1512), onBackground = Color(0xFFE0E4DF),
    surface = Color(0xFF0F1512), onSurface = Color(0xFFE0E4DF),
    surfaceVariant = Color(0xFF404943), onSurfaceVariant = Color(0xFFC0C9C1),
    surfaceContainer = Color(0xFF1B211E), surfaceContainerHigh = Color(0xFF252B28),
    outline = Color(0xFF8A938C),
)

@Composable
fun AppTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}

/** Priority colours (a stripe on each row), distinguishable without colour too: high has an icon. */
fun priorityColor(code: String, dark: Boolean): Color = when (code) {
    "high" -> if (dark) Color(0xFFFFB4AB) else Color(0xFFB3261E)
    "medium" -> if (dark) Color(0xFFFFD27A) else Color(0xFF8A5A00)
    else -> if (dark) Color(0xFF9DD5B3) else Color(0xFF2F6249)
}
