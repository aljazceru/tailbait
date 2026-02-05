package com.tailbait.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.tailbait.ui.theme.TailBaitDimensions
import com.tailbait.ui.theme.TailBaitShapeTokens
import com.tailbait.ui.theme.TailBaitTheme

/**
 * A reusable empty state view component that displays when no content is available.
 *
 * This component provides a user-friendly empty state with:
 * - A large icon representing the empty state
 * - A title describing the empty state
 * - An optional message providing additional context
 * - Optional action buttons (primary and secondary)
 *
 * Empty states improve UX by:
 * - Explaining why no content is shown
 * - Guiding users on what to do next
 * - Preventing user confusion
 *
 * @param icon The icon to display (default: search icon)
 * @param title The title text for the empty state
 * @param message Optional descriptive message
 * @param actionButtonText Optional primary action button text
 * @param onActionClick Optional primary action button click handler
 * @param secondaryActionText Optional secondary action button text
 * @param onSecondaryActionClick Optional secondary action button click handler
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun EmptyView(
    icon: ImageVector = Icons.Outlined.Search,
    title: String,
    message: String? = null,
    actionButtonText: String? = null,
    onActionClick: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(TailBaitDimensions.SpacingXXXL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(TailBaitDimensions.IconSizeHero),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )

            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingLG))

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            // Message
            if (message != null) {
                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingSM))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Primary Action Button
            if (actionButtonText != null && onActionClick != null) {
                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXXL))
                Button(
                    onClick = onActionClick,
                    shape = TailBaitShapeTokens.ButtonShape,
                    modifier = Modifier.height(TailBaitDimensions.ButtonHeight)
                ) {
                    Text(
                        text = actionButtonText,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Secondary Action Button
            if (secondaryActionText != null && onSecondaryActionClick != null) {
                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingSM))
                TextButton(
                    onClick = onSecondaryActionClick
                ) {
                    Text(
                        text = secondaryActionText,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * A compact empty view component for inline use within other components.
 *
 * This is a smaller version designed for cards, lists, or sections where
 * a full-screen empty state would be inappropriate.
 *
 * @param icon The icon to display
 * @param message The message text
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun CompactEmptyView(
    icon: ImageVector = Icons.Outlined.Info,
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(TailBaitDimensions.SpacingLG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(TailBaitDimensions.IconSizeXL),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingSM))

        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// Preview functions for design-time visualization
@Preview(name = "Empty View - Basic", showBackground = true)
@Composable
private fun EmptyViewBasicPreview() {
    TailBaitTheme {
        EmptyView(
            icon = Icons.Outlined.DevicesOther,
            title = "No Devices Found",
            message = "Start tracking to discover nearby Bluetooth devices"
        )
    }
}

@Preview(name = "Empty View - With Actions", showBackground = true)
@Composable
private fun EmptyViewWithActionsPreview() {
    TailBaitTheme {
        EmptyView(
            icon = Icons.Outlined.BluetoothSearching,
            title = "No Devices Detected",
            message = "Make sure Bluetooth is enabled and start scanning for devices",
            actionButtonText = "Start Scanning",
            onActionClick = {},
            secondaryActionText = "Learn More",
            onSecondaryActionClick = {}
        )
    }
}

@Preview(name = "Empty View - Alerts", showBackground = true)
@Composable
private fun EmptyViewAlertsPreview() {
    TailBaitTheme {
        EmptyView(
            icon = Icons.Outlined.NotificationsNone,
            title = "No Alerts",
            message = "You're all safe! No suspicious devices detected."
        )
    }
}

@Preview(name = "Compact Empty View", showBackground = true)
@Composable
private fun CompactEmptyViewPreview() {
    TailBaitTheme {
        CompactEmptyView(
            icon = Icons.Outlined.Search,
            message = "No results found"
        )
    }
}

@Preview(name = "Empty View - Dark Mode", showBackground = true)
@Composable
private fun EmptyViewDarkPreview() {
    TailBaitTheme(darkTheme = true) {
        EmptyView(
            icon = Icons.Outlined.DevicesOther,
            title = "No Devices Found",
            message = "Start tracking to discover nearby Bluetooth devices",
            actionButtonText = "Start Now",
            onActionClick = {}
        )
    }
}
