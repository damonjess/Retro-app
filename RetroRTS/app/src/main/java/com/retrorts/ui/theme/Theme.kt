package com.retrorts.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 90s Retro Color Palette
val RetroBlack = Color(0xFF0A0A0A)
val RetroPanel = Color(0xFF1A1A1A)
val RetroNeonCyan = Color(0xFF00F0FF)
val RetroNeonMagenta = Color(0xFFFF00FF)
val RetroNeonGreen = Color(0xFF39FF14)
val RetroYellow = Color(0xFFF5C542) // Used for alerts/accents

val RetroWhite = Color(0xFFFFFFFF)
val RetroGray = Color(0xFF808080)

private val RetroDarkColorScheme = darkColorScheme(
    primary = RetroNeonCyan,
    secondary = RetroNeonMagenta,
    tertiary = RetroNeonGreen,
    background = RetroBlack,
    surface = RetroPanel,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = RetroWhite,
    onSurface = RetroWhite,

)

// Fallback to Monospace for pixel feel if custom font is not loaded
val RetroFontFamily = FontFamily.Monospace

val RetroTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = RetroFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = 1.sp,
        color = RetroNeonCyan
    ),
    titleLarge = TextStyle(
        fontFamily = RetroFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        color = RetroNeonCyan
    ),
    bodyLarge = TextStyle(
        fontFamily = RetroFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = RetroWhite
    ),
    labelMedium = TextStyle(
        fontFamily = RetroFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = RetroGray
    )
)

@Composable
fun RetroTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RetroDarkColorScheme,
        typography = RetroTypography,
        content = content
    )
}
