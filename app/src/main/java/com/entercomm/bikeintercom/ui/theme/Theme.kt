package com.entercomm.bikeintercom.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// High contrast colors for accessibility
private val HighContrastWhite = Color(0xFFFFFFFF)
private val HighContrastGreen = Color(0xFF00FF99) // Brighter green
private val HighContrastCyan = Color(0xFF00FFFF) // Pure cyan
private val HighContrastRed = Color(0xFFFF4444) // Brighter red
private val HighContrastBlue = Color(0xFF4499FF) // Brighter blue
private val HighContrastBorder = Color(0xFF555555) // More visible borders

// High Contrast Color Scheme for accessibility
private val HighContrastColorScheme = darkColorScheme(
    // Core colors - brighter for visibility
    primary = HighContrastGreen,
    onPrimary = PitchBlack,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = HighContrastGreen,

    secondary = HighContrastCyan,
    onSecondary = PitchBlack,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = HighContrastCyan,

    tertiary = HighContrastBlue,
    onTertiary = PitchBlack,
    tertiaryContainer = DarkSurfaceVariant,
    onTertiaryContainer = HighContrastBlue,

    // Error colors
    error = HighContrastRed,
    onError = HighContrastWhite,
    errorContainer = DarkSurfaceVariant,
    onErrorContainer = HighContrastRed,

    // Background colors - pure black with white text
    background = PitchBlack,
    onBackground = HighContrastWhite,
    surface = PitchBlack, // Pure black instead of dark gray
    onSurface = HighContrastWhite,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = HighContrastWhite, // White instead of gray

    // Outline colors - more visible
    outline = HighContrastBorder,
    outlineVariant = HighContrastBorder,

    // Surface tint
    surfaceTint = HighContrastGreen,

    // Inverse colors
    inverseSurface = HighContrastWhite,
    inverseOnSurface = PitchBlack,
    inversePrimary = HighContrastGreen,
)

// Pitch Black Theme - Always use dark colors for this app
private val PitchBlackColorScheme = darkColorScheme(
    // Core colors
    primary = TechGreen,
    onPrimary = PitchBlack,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = TechGreen,

    secondary = TechCyan,
    onSecondary = PitchBlack,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = TechCyan,

    tertiary = TechBlue,
    onTertiary = PitchBlack,
    tertiaryContainer = DarkSurfaceVariant,
    onTertiaryContainer = TechBlue,

    // Error colors
    error = TechRed,
    onError = TextPrimary,
    errorContainer = DarkSurfaceVariant,
    onErrorContainer = TechRed,

    // Background colors
    background = PitchBlack,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,

    // Outline colors
    outline = DarkBorder,
    outlineVariant = TextTertiary,

    // Surface tint
    surfaceTint = TechGreen,

    // Inverse colors
    inverseSurface = TextPrimary,
    inverseOnSurface = PitchBlack,
    inversePrimary = TechGreen,
)

// Legacy color schemes (kept for fallback)
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

@Composable
fun EnterCommTheme(
    darkTheme: Boolean = true, // Always dark theme for this app
    usePitchBlack: Boolean = true, // Control whether to use custom pitch black theme
    largeTextMode: Boolean = false, // Accessibility: larger text
    highContrastMode: Boolean = false, // Accessibility: higher contrast colors
    content: @Composable () -> Unit,
) {
    // Select color scheme based on high contrast setting
    val colorScheme = when {
        highContrastMode -> HighContrastColorScheme
        usePitchBlack -> PitchBlackColorScheme
        else -> DarkColorScheme
    }

    // Select typography based on large text setting
    val typography = if (largeTextMode) LargeTypography else Typography

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Set status bar to pure black for immersive experience
            window.statusBarColor = PitchBlack.toArgb()
            // Always use light content on dark status bar
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content,
    )
}
