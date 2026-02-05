package com.tailbait.ui.theme

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

/**
 * TailBait Light Color Scheme
 * Clean, minimal iOS-style aesthetic with steel blue accent
 */
private val TailBaitLightColorScheme = lightColorScheme(
    // Primary
    primary = TailBaitLightColors.Primary,
    onPrimary = TailBaitLightColors.OnPrimary,
    primaryContainer = TailBaitLightColors.PrimaryContainer,
    onPrimaryContainer = TailBaitLightColors.OnPrimaryContainer,

    // Secondary
    secondary = TailBaitLightColors.Secondary,
    onSecondary = TailBaitLightColors.OnSecondary,
    secondaryContainer = TailBaitLightColors.SecondaryContainer,
    onSecondaryContainer = TailBaitLightColors.OnSecondaryContainer,

    // Tertiary
    tertiary = TailBaitLightColors.Tertiary,
    onTertiary = TailBaitLightColors.OnTertiary,
    tertiaryContainer = TailBaitLightColors.TertiaryContainer,
    onTertiaryContainer = TailBaitLightColors.OnTertiaryContainer,

    // Error
    error = TailBaitLightColors.Error,
    onError = TailBaitLightColors.OnError,
    errorContainer = TailBaitLightColors.ErrorContainer,
    onErrorContainer = TailBaitLightColors.OnErrorContainer,

    // Background & Surface
    background = TailBaitLightColors.Background,
    onBackground = TailBaitLightColors.OnBackground,
    surface = TailBaitLightColors.Surface,
    onSurface = TailBaitLightColors.OnSurface,
    surfaceVariant = TailBaitLightColors.SurfaceVariant,
    onSurfaceVariant = TailBaitLightColors.OnSurfaceVariant,
    surfaceTint = TailBaitLightColors.SurfaceTint,

    // Outline
    outline = TailBaitLightColors.Outline,
    outlineVariant = TailBaitLightColors.OutlineVariant,

    // Inverse
    inverseSurface = TailBaitLightColors.InverseSurface,
    inverseOnSurface = TailBaitLightColors.InverseOnSurface,
    inversePrimary = TailBaitLightColors.InversePrimary,

    // Scrim
    scrim = TailBaitLightColors.Scrim
)

/**
 * TailBait Dark Color Scheme
 * OLED-friendly black with brighter blue accent
 */
private val TailBaitDarkColorScheme = darkColorScheme(
    // Primary
    primary = TailBaitDarkColors.Primary,
    onPrimary = TailBaitDarkColors.OnPrimary,
    primaryContainer = TailBaitDarkColors.PrimaryContainer,
    onPrimaryContainer = TailBaitDarkColors.OnPrimaryContainer,

    // Secondary
    secondary = TailBaitDarkColors.Secondary,
    onSecondary = TailBaitDarkColors.OnSecondary,
    secondaryContainer = TailBaitDarkColors.SecondaryContainer,
    onSecondaryContainer = TailBaitDarkColors.OnSecondaryContainer,

    // Tertiary
    tertiary = TailBaitDarkColors.Tertiary,
    onTertiary = TailBaitDarkColors.OnTertiary,
    tertiaryContainer = TailBaitDarkColors.TertiaryContainer,
    onTertiaryContainer = TailBaitDarkColors.OnTertiaryContainer,

    // Error
    error = TailBaitDarkColors.Error,
    onError = TailBaitDarkColors.OnError,
    errorContainer = TailBaitDarkColors.ErrorContainer,
    onErrorContainer = TailBaitDarkColors.OnErrorContainer,

    // Background & Surface
    background = TailBaitDarkColors.Background,
    onBackground = TailBaitDarkColors.OnBackground,
    surface = TailBaitDarkColors.Surface,
    onSurface = TailBaitDarkColors.OnSurface,
    surfaceVariant = TailBaitDarkColors.SurfaceVariant,
    onSurfaceVariant = TailBaitDarkColors.OnSurfaceVariant,
    surfaceTint = TailBaitDarkColors.SurfaceTint,

    // Outline
    outline = TailBaitDarkColors.Outline,
    outlineVariant = TailBaitDarkColors.OutlineVariant,

    // Inverse
    inverseSurface = TailBaitDarkColors.InverseSurface,
    inverseOnSurface = TailBaitDarkColors.InverseOnSurface,
    inversePrimary = TailBaitDarkColors.InversePrimary,

    // Scrim
    scrim = TailBaitDarkColors.Scrim
)

/**
 * TailBait Theme - Clean, minimal, professional design
 *
 * @param darkTheme Whether to use dark theme (defaults to system preference)
 * @param dynamicColor Whether to use Material You dynamic colors (disabled by default for consistent branding)
 * @param content The composable content to display
 */
@Composable
fun TailBaitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disabled by default for consistent branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> TailBaitDarkColorScheme
        else -> TailBaitLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Use surface color for status bar (cleaner, modern look)
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TailBaitTypography,
        shapes = TailBaitShapes,
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// LEGACY COMPATIBILITY
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Legacy theme name - use TailBaitTheme instead
 */
@Deprecated(
    message = "Use TailBaitTheme instead",
    replaceWith = ReplaceWith("TailBaitTheme(darkTheme, dynamicColor, content)")
)
@Composable
fun BLETrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = TailBaitTheme(darkTheme, dynamicColor, content)
