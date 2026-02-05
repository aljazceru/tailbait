package com.tailbait.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * TailBait typography system - Complete Material 3 typography scale
 *
 * Uses system fonts (Roboto on Android) for native feel and accessibility.
 * SemiBold weight for titles creates visual hierarchy without being heavy.
 */
val TailBaitTypography = Typography(

    // ═══════════════════════════════════════════════════════════════════════════
    // DISPLAY - Large, prominent headlines (onboarding, hero sections)
    // ═══════════════════════════════════════════════════════════════════════════

    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // ═══════════════════════════════════════════════════════════════════════════
    // HEADLINE - Section headers, emphasis text
    // ═══════════════════════════════════════════════════════════════════════════

    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // ═══════════════════════════════════════════════════════════════════════════
    // TITLE - Screen titles, card headers, list section headers
    // ═══════════════════════════════════════════════════════════════════════════

    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // ═══════════════════════════════════════════════════════════════════════════
    // BODY - Primary content text, descriptions, paragraphs
    // ═══════════════════════════════════════════════════════════════════════════

    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // ═══════════════════════════════════════════════════════════════════════════
    // LABEL - Buttons, badges, chips, metadata, captions
    // ═══════════════════════════════════════════════════════════════════════════

    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

// ═══════════════════════════════════════════════════════════════════════════════
// TYPOGRAPHY USAGE GUIDE
// ═══════════════════════════════════════════════════════════════════════════════
//
// | Style          | Weight    | Size | Use Case                              |
// |----------------|-----------|------|---------------------------------------|
// | displayLarge   | Normal    | 57sp | Hero text, splash screen             |
// | displayMedium  | Normal    | 45sp | Large promotional text               |
// | displaySmall   | Normal    | 36sp | Onboarding headlines                 |
// | headlineLarge  | SemiBold  | 32sp | Main screen titles                   |
// | headlineMedium | SemiBold  | 28sp | Section headers in settings          |
// | headlineSmall  | SemiBold  | 24sp | Dialog titles, emphasis              |
// | titleLarge     | SemiBold  | 22sp | TopAppBar titles                     |
// | titleMedium    | Medium    | 16sp | Card titles, device names            |
// | titleSmall     | Medium    | 14sp | Section headers in lists             |
// | bodyLarge      | Normal    | 16sp | Primary content, descriptions        |
// | bodyMedium     | Normal    | 14sp | Secondary content, list items        |
// | bodySmall      | Normal    | 12sp | Timestamps, MAC addresses            |
// | labelLarge     | Medium    | 14sp | Button text, primary labels          |
// | labelMedium    | Medium    | 12sp | Badges, chips, secondary labels      |
// | labelSmall     | Medium    | 11sp | Captions, helper text                |
// ═══════════════════════════════════════════════════════════════════════════════

// Legacy compatibility - keeping old reference
@Deprecated("Use TailBaitTypography instead", ReplaceWith("TailBaitTypography"))
val Typography = TailBaitTypography
