package com.tailbait.ui.screens.permissions


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.tailbait.ui.theme.TailBaitDimensions
import com.tailbait.ui.theme.TailBaitShapeTokens

/**
 * Reusable permission card component that displays permission status
 * and allows users to request permissions.
 */
@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    isRequired: Boolean,
    onRequestPermission: () -> Unit,
    onShowRationale: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted) {
                onShowRationale()
            },

        shape = TailBaitShapeTokens.CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TailBaitDimensions.CardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon
            Icon(
                imageVector = when {
                    isGranted -> Icons.Default.CheckCircle
                    isRequired -> Icons.Default.Warning
                    else -> Icons.Default.Info
                },
                contentDescription = if (isGranted) "Granted" else "Not Granted",
                tint = when {
                    isGranted -> MaterialTheme.colorScheme.primary
                    isRequired -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(TailBaitDimensions.IconSizeLarge)
            )

            Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingLG))

            // Permission Info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (!isRequired) {
                        Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingSM))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = TailBaitShapeTokens.BadgeShape,
                            modifier = Modifier.padding(
                                horizontal = TailBaitDimensions.SpacingXS,
                                vertical = TailBaitDimensions.SpacingXXS
                            )
                        ) {
                            Text(
                                text = "Optional",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(
                                    horizontal = TailBaitDimensions.SpacingSM,
                                    vertical = TailBaitDimensions.SpacingXXS
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXS))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isGranted) {
                    Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXS))
                    Text(
                        text = "✓ Permission granted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingSM))

            // Action Button
            if (!isGranted) {
                IconButton(
                    onClick = onRequestPermission
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Grant Permission",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Compact permission status indicator for use in settings or status screens
 */
@Composable
fun PermissionStatusIndicator(
    permissionName: String,
    isGranted: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                vertical = TailBaitDimensions.SpacingSM,
                horizontal = TailBaitDimensions.SpacingLG
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = if (isGranted) "Granted" else "Denied",
            tint = if (isGranted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(TailBaitDimensions.IconSizeMedium)
        )

        Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingMD))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = permissionName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (isGranted) "Granted" else "Not Granted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Permission group status card for dashboard/home screen
 */
@Composable
fun PermissionGroupStatusCard(
    title: String,
    description: String,
    allGranted: Boolean,
    grantedCount: Int,
    totalCount: Int,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onManageClick() },

        shape = TailBaitShapeTokens.CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (allGranted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TailBaitDimensions.CardPadding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (allGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (allGranted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    modifier = Modifier.size(TailBaitDimensions.IconSizeLarge)
                )

                Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingLG))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (allGranted) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )

                    Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXS))

                    Text(
                        text = "$grantedCount of $totalCount permissions granted",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (allGranted) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Manage",
                    tint = if (allGranted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
            }

            if (!allGranted) {
                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingMD))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
