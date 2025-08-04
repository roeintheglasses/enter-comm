package com.entercomm.bikeintercom.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
    inversePrimary = TechGreen
)

// Legacy color schemes (kept for fallback)
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun EnterCommTheme(
    darkTheme: Boolean = true, // Always dark theme for this app
    usePitchBlack: Boolean = true, // Control whether to use custom pitch black theme
    content: @Composable () -> Unit
) {
    // Always use pitch black theme for this app, ignore system preferences
    val colorScheme = if (usePitchBlack) {
        PitchBlackColorScheme
    } else {
        // Fallback to standard dark theme if needed
        DarkColorScheme
    }
    
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
        typography = Typography,
        content = content
    )
}