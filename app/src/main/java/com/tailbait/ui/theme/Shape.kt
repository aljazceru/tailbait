package com.tailbait.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * TailBait shape system - modern, consistent corner radii
 */
val TailBaitShapes =
    Shapes(
        // Pills, chips, small badges
        // Increased from 4dp
        extraSmall = RoundedCornerShape(8.dp),
        // Input fields, small buttons, tags
        // Increased from 8dp
        small = RoundedCornerShape(12.dp),
        // Cards, dialogs, standard containers
        // Increased from 12dp
        medium = RoundedCornerShape(16.dp),
        // Large cards, bottom sheets, modals
        // Increased from 16dp
        large = RoundedCornerShape(24.dp),
        // Full-width cards, hero sections
        // Increased from 24dp
        extraLarge = RoundedCornerShape(32.dp),
    )

/**
 * Custom shape tokens for specific UI elements
 */
object TailBaitShapeTokens {
    /** iOS-style rounded buttons */
    val ButtonShape = RoundedCornerShape(16.dp) // Increased from 10dp

    /** Standard card corners */
    val CardShape = RoundedCornerShape(20.dp) // Increased from 12dp

    /** Alert level badges */
    val BadgeShape = RoundedCornerShape(8.dp) // Increased from 6dp

    /** Filter chips and tags */
    val ChipShape = RoundedCornerShape(12.dp) // Increased from 8dp

    /** Bottom sheets with rounded top corners */
    val BottomSheetShape =
        RoundedCornerShape(
            // Increased from 20dp
            topStart = 28.dp,
            topEnd = 28.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        )

    /** Floating action buttons */
    val FABShape = RoundedCornerShape(20.dp) // Increased from 16dp

    /** Floating action buttons (alias) */
    val FabShape = FABShape

    /** Search bar */
    val SearchBarShape = RoundedCornerShape(24.dp) // Fully rounded style

    /** Input fields */
    val InputShape = RoundedCornerShape(16.dp) // Increased from 10dp

    /** Dialog containers */
    val DialogShape = RoundedCornerShape(28.dp) // Increased from 16dp

    /** Threat score indicator (circular) */
    val CircularShape = RoundedCornerShape(50)
}
