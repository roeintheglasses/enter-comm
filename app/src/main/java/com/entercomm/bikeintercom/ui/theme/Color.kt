package com.entercomm.bikeintercom.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Pitch Black Theme Colors
val PitchBlack = Color(0xFF000000)
val DarkSurface = Color(0xFF0A0A0A)
val DarkSurfaceVariant = Color(0xFF141414)
val DarkSurfaceElevated = Color(0xFF1C1C1C)
val DarkBorder = Color(0xFF2A2A2A)
val DarkBorderLight = Color(0xFF3A3A3A)

// Primary Accent - Neon Green (cyber/tech feel)
val TechGreen = Color(0xFF00FF88)           // Bright neon green
val TechGreenDark = Color(0xFF00CC6A)       // Darker green for gradients
val TechGreenMuted = Color(0xFF00B85C)      // Muted green for backgrounds

// Secondary Accent - Cyan/Teal
val TechCyan = Color(0xFF00E5FF)            // Bright cyan
val TechCyanDark = Color(0xFF00B8D4)        // Darker cyan
val TechCyanMuted = Color(0xFF0097A7)       // Muted cyan

// Warning/Active - Orange/Amber
val TechOrange = Color(0xFFFF9100)          // Bright orange
val TechOrangeDark = Color(0xFFFF6D00)      // Darker orange
val TechOrangeMuted = Color(0xFFE65100)     // Muted orange

// Danger/Recording - Red
val TechRed = Color(0xFFFF3D71)             // Bright red-pink
val TechRedDark = Color(0xFFE61E4D)         // Darker red
val TechRedMuted = Color(0xFFBF1541)        // Muted red

// Info - Blue
val TechBlue = Color(0xFF3D8BFF)            // Bright blue
val TechBlueDark = Color(0xFF2979FF)        // Darker blue
val TechBlueMuted = Color(0xFF1565C0)       // Muted blue

// Purple accent for highlights
val TechPurple = Color(0xFFBB86FC)          // Light purple
val TechPurpleDark = Color(0xFF9C27B0)      // Darker purple

// Text Colors
val TextPrimary = Color(0xFFFFFFFF)         // Pure white
val TextSecondary = Color(0xFFB8B8B8)       // Light gray
val TextTertiary = Color(0xFF6E6E6E)        // Medium gray
val TextDisabled = Color(0xFF454545)        // Disabled gray

// Gradient definitions
val GradientGreen = Brush.linearGradient(
    colors = listOf(TechGreen, TechGreenDark)
)
val GradientCyan = Brush.linearGradient(
    colors = listOf(TechCyan, TechCyanDark)
)
val GradientRed = Brush.linearGradient(
    colors = listOf(TechRed, TechRedDark)
)
val GradientOrange = Brush.linearGradient(
    colors = listOf(TechOrange, TechOrangeDark)
)
val GradientBlue = Brush.linearGradient(
    colors = listOf(TechBlue, TechBlueDark)
)
val GradientPurple = Brush.linearGradient(
    colors = listOf(TechPurple, TechPurpleDark)
)

// Radial gradients for glow effects
fun glowGradient(color: Color) = Brush.radialGradient(
    colors = listOf(
        color.copy(alpha = 0.4f),
        color.copy(alpha = 0.1f),
        Color.Transparent
    )
)

// Surface gradients
val SurfaceGradient = Brush.verticalGradient(
    colors = listOf(DarkSurface, PitchBlack)
)

// Legacy colors (kept for compatibility)
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)