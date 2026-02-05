package com.tailbait.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TailBait color system - Clean, minimal, Apple-like aesthetic
 *
 * Primary: Steel Blue (#007AFF) - Trust, security, professionalism
 * Background: iOS-style neutral grays for grouped content
 * Alert colors: Semantic status indicators
 */

// ═══════════════════════════════════════════════════════════════════════════════
// LIGHT THEME COLORS
// ═══════════════════════════════════════════════════════════════════════════════

object TailBaitLightColors {
    // === PRIMARY (Vibrant Blue) ===
    val Primary = Color(0xFF2563EB)              // Brighter, more saturated blue
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFDBEAFE)
    val OnPrimaryContainer = Color(0xFF1E3A8A)

    // === SECONDARY (Electric Indigo) ===
    val Secondary = Color(0xFF4F46E5)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFE0E7FF)
    val OnSecondaryContainer = Color(0xFF312E81)

    // === TERTIARY (Emerald) ===
    val Tertiary = Color(0xFF10B981)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFD1FAE5)
    val OnTertiaryContainer = Color(0xFF064E3B)

    // === SURFACE HIERARCHY ===
    val Background = Color(0xFFF8FAFC)           // Very subtle slate tint, not flat gray
    val OnBackground = Color(0xFF0F172A)         // Slate 900
    val Surface = Color(0xFFFFFFFF)              // Pure white
    val OnSurface = Color(0xFF0F172A)
    val SurfaceVariant = Color(0xFFF1F5F9)       // Slate 100
    val OnSurfaceVariant = Color(0xFF475569)     // Slate 600
    val SurfaceTint = Color(0xFF2563EB)

    // === SURFACE CONTAINERS ===
    val SurfaceContainerLowest = Color(0xFFFFFFFF)
    val SurfaceContainerLow = Color(0xFFF8FAFC)
    val SurfaceContainer = Color(0xFFF1F5F9)
    val SurfaceContainerHigh = Color(0xFFE2E8F0)
    val SurfaceContainerHighest = Color(0xFFCBD5E1)

    // === OUTLINE (Softer) ===
    val Outline = Color(0xFF94A3B8)              // Slate 400
    val OutlineVariant = Color(0xFFCBD5E1)       // Slate 300

    // === ERROR ===
    val Error = Color(0xFFEF4444)                // Vibrant Red
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFFEE2E2)
    val OnErrorContainer = Color(0xFF7F1D1D)

    // === INVERSE ===
    val InverseSurface = Color(0xFF0F172A)
    val InverseOnSurface = Color(0xFFF1F5F9)
    val InversePrimary = Color(0xFF60A5FA)

    // === SCRIM ===
    val Scrim = Color(0xFF000000)
}

// ═══════════════════════════════════════════════════════════════════════════════
// DARK THEME COLORS (Deep Night)
// ═══════════════════════════════════════════════════════════════════════════════

object TailBaitDarkColors {
    // === PRIMARY ===
    val Primary = Color(0xFF3B82F6)              // Lighter blue for dark mode
    val OnPrimary = Color(0xFF1E3A8A)
    val PrimaryContainer = Color(0xFF1E40AF)
    val OnPrimaryContainer = Color(0xFFDBEAFE)

    // === SECONDARY ===
    val Secondary = Color(0xFF6366F1)
    val OnSecondary = Color(0xFF312E81)
    val SecondaryContainer = Color(0xFF3730A3)
    val OnSecondaryContainer = Color(0xFFE0E7FF)

    // === TERTIARY ===
    val Tertiary = Color(0xFF34D399)
    val OnTertiary = Color(0xFF064E3B)
    val TertiaryContainer = Color(0xFF065F46)
    val OnTertiaryContainer = Color(0xFFD1FAE5)

    // === SURFACE HIERARCHY ===
    val Background = Color(0xFF0F172A)           // Deep Slate 900
    val OnBackground = Color(0xFFF8FAFC)
    val Surface = Color(0xFF1E293B)              // Slate 800 - distinct card color
    val OnSurface = Color(0xFFF8FAFC)
    val SurfaceVariant = Color(0xFF334155)       // Slate 700
    val OnSurfaceVariant = Color(0xFF94A3B8)     // Slate 400
    val SurfaceTint = Color(0xFF3B82F6)

    // === SURFACE CONTAINERS ===
    val SurfaceContainerLowest = Color(0xFF020617) // Slate 950
    val SurfaceContainerLow = Color(0xFF0F172A)    // Slate 900
    val SurfaceContainer = Color(0xFF1E293B)       // Slate 800
    val SurfaceContainerHigh = Color(0xFF334155)   // Slate 700
    val SurfaceContainerHighest = Color(0xFF475569) // Slate 600

    // === OUTLINE ===
    val Outline = Color(0xFF475569)
    val OutlineVariant = Color(0xFF334155)

    // === ERROR ===
    val Error = Color(0xFFF87171)
    val OnError = Color(0xFF450A0A)
    val ErrorContainer = Color(0xFF7F1D1D)
    val OnErrorContainer = Color(0xFFFEE2E2)

    // === INVERSE ===
    val InverseSurface = Color(0xFFF8FAFC)
    val InverseOnSurface = Color(0xFF0F172A)
    val InversePrimary = Color(0xFF2563EB)

    // === SCRIM ===
    val Scrim = Color(0xFF000000)
}

// ═══════════════════════════════════════════════════════════════════════════════
// SEMANTIC ALERT COLORS (Both themes)
// ═══════════════════════════════════════════════════════════════════════════════

object AlertColors {
    // === LOW (Safe/Green) ===
    val Low = Color(0xFF34C759)
    val LowContainer = Color(0xFFD4F5DC)
    val OnLow = Color(0xFFFFFFFF)
    val OnLowContainer = Color(0xFF002111)

    // Dark theme variants
    val LowDark = Color(0xFF30D158)
    val LowContainerDark = Color(0xFF1A3D25)
    val OnLowContainerDark = Color(0xFFD4F5DC)

    // === MEDIUM (Caution/Orange) ===
    val Medium = Color(0xFFFF9500)
    val MediumContainer = Color(0xFFFFE4CC)
    val OnMedium = Color(0xFFFFFFFF)
    val OnMediumContainer = Color(0xFF331E00)

    // Dark theme variants
    val MediumDark = Color(0xFFFF9F0A)
    val MediumContainerDark = Color(0xFF3D2800)
    val OnMediumContainerDark = Color(0xFFFFE4CC)

    // === HIGH (Warning/Deep Orange) ===
    val High = Color(0xFFFF6B00)
    val HighContainer = Color(0xFFFFDBC9)
    val OnHigh = Color(0xFFFFFFFF)
    val OnHighContainer = Color(0xFF2D1500)

    // Dark theme variants
    val HighDark = Color(0xFFFF8C42)
    val HighContainerDark = Color(0xFF3D1E00)
    val OnHighContainerDark = Color(0xFFFFDBC9)

    // === CRITICAL (Danger/Red) ===
    val Critical = Color(0xFFFF3B30)
    val CriticalContainer = Color(0xFFFFDAD6)
    val OnCritical = Color(0xFFFFFFFF)
    val OnCriticalContainer = Color(0xFF410002)

    // Dark theme variants
    val CriticalDark = Color(0xFFFF453A)
    val CriticalContainerDark = Color(0xFF5C0008)
    val OnCriticalContainerDark = Color(0xFFFFDAD6)
}

// ═══════════════════════════════════════════════════════════════════════════════
// SPECIAL STATE COLORS
// ═══════════════════════════════════════════════════════════════════════════════

object StateColors {
    // === LIGHT THEME ===
    val Scanning = Color(0xFF007AFF)             // Active scanning
    val ScanningBackground = Color(0xFFD6E4FF)
    val Whitelisted = Color(0xFF34C759)          // Known/trusted device
    val WhitelistedBackground = Color(0xFFD4F5DC)
    val Unknown = Color(0xFF8E8E93)              // Unknown device
    val UnknownBackground = Color(0xFFF2F2F7)
    val Inactive = Color(0xFFC7C7CC)             // Disabled/inactive

    // === DARK THEME ===
    val ScanningDark = Color(0xFF0A84FF)
    val ScanningBackgroundDark = Color(0xFF003366)
    val WhitelistedDark = Color(0xFF30D158)
    val WhitelistedBackgroundDark = Color(0xFF1A3D25)
    val UnknownDark = Color(0xFF636366)
    val UnknownBackgroundDark = Color(0xFF2C2C2E)
    val InactiveDark = Color(0xFF48484A)
}

// ═══════════════════════════════════════════════════════════════════════════════
// LEGACY COMPATIBILITY (for gradual migration)
// ═══════════════════════════════════════════════════════════════════════════════

// Keep old names temporarily for compatibility during migration
@Deprecated("Use TailBaitLightColors.Primary instead", ReplaceWith("TailBaitLightColors.Primary"))
val Purple40 = TailBaitLightColors.Primary

@Deprecated("Use TailBaitDarkColors.Primary instead", ReplaceWith("TailBaitDarkColors.Primary"))
val Purple80 = TailBaitDarkColors.Primary

@Deprecated("Use AlertColors.Low instead", ReplaceWith("AlertColors.Low"))
val AlertLow = AlertColors.Low

@Deprecated("Use AlertColors.Medium instead", ReplaceWith("AlertColors.Medium"))
val AlertMedium = AlertColors.Medium

@Deprecated("Use AlertColors.High instead", ReplaceWith("AlertColors.High"))
val AlertHigh = AlertColors.High

@Deprecated("Use AlertColors.Critical instead", ReplaceWith("AlertColors.Critical"))
val AlertCritical = AlertColors.Critical
