package com.obd2.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Dark automotive palette
val Bg = Color(0xFF0B0F17)
val Surface = Color(0xFF121826)
val SurfaceHigh = Color(0xFF1A2233)
val Primary = Color(0xFF4FC3F7)
val PrimaryDim = Color(0xFF2B7EA8)
val Accent = Color(0xFFFF7043)
val Green = Color(0xFF66BB6A)
val Red = Color(0xFFEF5350)
val Amber = Color(0xFFFFCA28)
val TextMain = Color(0xFFE8EEF7)
val TextDim = Color(0xFF93A1B5)

private val DarkScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color(0xFF00293B),
    secondary = Accent,
    onSecondary = Color(0xFF331200),
    background = Bg,
    onBackground = TextMain,
    surface = Surface,
    onSurface = TextMain,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextDim,
    error = Red,
    onError = Color(0xFF2A0000),
    outline = Color(0xFF2E3A4D),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.4.sp),
)

@Composable
fun Obd2Theme(content: @Composable () -> Unit) {
    // The app is dark-first per design requirements; keep dark even in light system theme.
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content,
    )
}
