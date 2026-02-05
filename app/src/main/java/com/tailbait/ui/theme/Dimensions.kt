package com.tailbait.ui.theme

import androidx.compose.ui.unit.dp

/**
 * TailBait dimension system - consistent spacing, sizing, and elevation
 */
object TailBaitDimensions {

    // ═══════════════════════════════════════════════════════════════════════
    // SPACING SCALE
    // ═══════════════════════════════════════════════════════════════════════

    /** 2.dp - Minimal spacing */
    val SpacingXXS = 2.dp

    /** 4.dp - Extra small spacing */
    val SpacingXS = 4.dp

    /** 8.dp - Small spacing */
    val SpacingSM = 8.dp

    /** 12.dp - Medium spacing */
    val SpacingMD = 12.dp

    /** 16.dp - Large spacing (default) */
    val SpacingLG = 16.dp

    /** 20.dp - Extra large spacing */
    val SpacingXL = 20.dp

    /** 24.dp - XXL spacing */
    val SpacingXXL = 24.dp

    /** 32.dp - XXXL spacing */
    val SpacingXXXL = 32.dp

    /** 48.dp - Section spacing */
    val SpacingSection = 48.dp

    // ═══════════════════════════════════════════════════════════════════════
    // CONTENT PADDING
    // ═══════════════════════════════════════════════════════════════════════

    /** Horizontal padding for screen content */
    val ContentPaddingHorizontal = 16.dp

    /** Vertical padding for screen content */
    val ContentPaddingVertical = 16.dp

    /** Internal card padding */
    val CardPadding = 16.dp

    /** List item vertical padding */
    val ListItemPaddingVertical = 12.dp

    /** List item horizontal padding */
    val ListItemPaddingHorizontal = 16.dp

    // ═══════════════════════════════════════════════════════════════════════
    // ICON SIZES
    // ═══════════════════════════════════════════════════════════════════════

    /** 16.dp - Small icons (metadata, badges) */
    val IconSizeSmall = 16.dp

    /** 20.dp - Default inline icons */
    val IconSizeDefault = 20.dp

    /** 24.dp - Medium icons (actions, list items) */
    val IconSizeMedium = 24.dp

    /** 32.dp - Large icons (cards, avatars) */
    val IconSizeLarge = 32.dp

    /** 48.dp - XL icons (status indicators) */
    val IconSizeXL = 48.dp

    /** 72.dp - Hero icons (empty states) */
    val IconSizeHero = 72.dp

    // ═══════════════════════════════════════════════════════════════════════
    // BUTTON SIZES
    // ═══════════════════════════════════════════════════════════════════════

    /** 50.dp - Primary button height (larger touch target) */
    val ButtonHeight = 50.dp

    /** 44.dp - Standard button height */
    val ButtonHeightMedium = 44.dp

    /** 36.dp - Small/compact button height */
    val ButtonHeightSmall = 36.dp

    /** 32.dp - Chip/tag height */
    val ChipHeight = 32.dp

    /** 48.dp - Minimum touch target (accessibility) */
    val MinTouchTarget = 48.dp

    // ═══════════════════════════════════════════════════════════════════════
    // AVATAR SIZES
    // ═══════════════════════════════════════════════════════════════════════

    /** 32.dp - Small avatar */
    val AvatarSizeSmall = 32.dp

    /** 40.dp - Medium avatar (list items) */
    val AvatarSizeMedium = 40.dp

    /** 56.dp - Large avatar (cards) */
    val AvatarSizeLarge = 56.dp

    /** 80.dp - XL avatar (profiles) */
    val AvatarSizeXL = 80.dp

    // ═══════════════════════════════════════════════════════════════════════
    // THREAT INDICATOR SIZES
    // ═══════════════════════════════════════════════════════════════════════

    /** 64.dp - Standard threat indicator */
    val ThreatIndicatorSize = 64.dp

    /** 48.dp - Compact threat indicator */
    val ThreatIndicatorSizeSmall = 48.dp

    /** 80.dp - Large threat indicator (detail view) */
    val ThreatIndicatorSizeLarge = 80.dp

    // ═══════════════════════════════════════════════════════════════════════
    // ELEVATION
    // ═══════════════════════════════════════════════════════════════════════

    /** 0.dp - No elevation (flat) */
    val ElevationNone = 0.dp

    /** 1.dp - Subtle elevation */
    val ElevationLow = 1.dp

    /** 2.dp - Card elevation */
    val ElevationMedium = 2.dp

    /** 4.dp - FAB, modal elevation */
    val ElevationHigh = 4.dp

    /** 8.dp - Dialog elevation */
    val ElevationHighest = 8.dp

    // ═══════════════════════════════════════════════════════════════════════
    // BORDER & DIVIDER
    // ═══════════════════════════════════════════════════════════════════════

    /** 0.5.dp - Hairline dividers */
    val DividerThickness = 0.5.dp

    /** 1.dp - Standard border width */
    val BorderWidth = 1.dp

    /** 2.dp - Thick border (focus, selection) */
    val BorderWidthThick = 2.dp

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT SPECIFIC
    // ═══════════════════════════════════════════════════════════════════════

    /** TopAppBar height */
    val TopAppBarHeight = 64.dp

    /** Search bar height */
    val SearchBarHeight = 48.dp

    /** Navigation bar height */
    val BottomNavHeight = 80.dp

    /** FAB size */
    val FABSize = 56.dp

    /** Progress bar height */
    val ProgressBarHeight = 4.dp

    /** Slider track height */
    val SliderTrackHeight = 4.dp

    /** Progress stroke width */
    val ProgressStrokeWidth = 4.dp

    /** Progress stroke width small */
    val ProgressStrokeWidthSmall = 3.dp

    /** Minimum card height */
    val CardMinHeight = 100.dp

    // ═══════════════════════════════════════════════════════════════════════
    // ONBOARDING SPECIFIC
    // ═══════════════════════════════════════════════════════════════════════

    /** Onboarding icon container size */
    val OnboardingIconSize = 120.dp

    /** Onboarding icon inner size */
    val OnboardingIconInnerSize = 64.dp
}
