package com.tailbait.ui.screens.alert

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Alert severity levels with associated colors and icons.
 * Uses semantic alert colors from the TailBait design system.
 */
enum class AlertLevel(
    val displayName: String,
    val icon: ImageVector
) {
    LOW(
        displayName = "Low",
        icon = Icons.Outlined.Info
    ),
    MEDIUM(
        displayName = "Medium",
        icon = Icons.Outlined.Warning
    ),
    HIGH(
        displayName = "High",
        icon = Icons.Filled.Warning
    ),
    CRITICAL(
        displayName = "Critical",
        icon = Icons.Filled.Error
    );

    /**
     * Get container color based on theme
     */
    @Composable
    fun containerColor(): Color = when (this) {
        LOW -> AlertColors.LowContainer
        MEDIUM -> AlertColors.MediumContainer
        HIGH -> AlertColors.HighContainer
        CRITICAL -> AlertColors.CriticalContainer
    }

    /**
     * Get content color based on theme
     */
    @Composable
    fun contentColor(): Color = when (this) {
        LOW -> AlertColors.OnLowContainer
        MEDIUM -> AlertColors.OnMediumContainer
        HIGH -> AlertColors.OnHighContainer
        CRITICAL -> AlertColors.OnCriticalContainer
    }

    /**
     * Get primary color (for indicators, icons)
     */
    @Composable
    fun primaryColor(): Color = when (this) {
        LOW -> AlertColors.Low
        MEDIUM -> AlertColors.Medium
        HIGH -> AlertColors.High
        CRITICAL -> AlertColors.Critical
    }

    companion object {
        fun fromString(level: String): AlertLevel {
            return when (level.uppercase()) {
                "LOW" -> LOW
                "MEDIUM" -> MEDIUM
                "HIGH" -> HIGH
                "CRITICAL" -> CRITICAL
                else -> LOW
            }
        }
    }
}

/**
 * Alert card component showing alert summary.
 *
 * @param alert The alert to display
 * @param onClick Callback when card is clicked
 * @param modifier Modifier for customization
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertCard(
    alert: AlertHistory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alertLevel = AlertLevel.fromString(alert.alertLevel)

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        ),

        shape = TailBaitShapeTokens.CardShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TailBaitDimensions.CardPadding),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Alert level badge
                AlertLevelBadge(
                    alertLevel = alertLevel,
                    isDismissed = alert.isDismissed
                )

                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingSM))

                // Title
                Text(
                    text = alert.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXS))

                // Message preview
                Text(
                    text = alert.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingMD))

                // Metadata
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingLG),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Timestamp
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingXS)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(TailBaitDimensions.IconSizeSmall),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatRelativeTime(alert.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Device count
                    val deviceCount = parseDeviceCount(alert.deviceAddresses)
                    if (deviceCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingXS)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(TailBaitDimensions.IconSizeSmall),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$deviceCount device${if (deviceCount != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(TailBaitDimensions.SpacingMD))

            // Threat score
            ThreatScoreIndicator(
                threatScore = alert.threatScore,
                size = TailBaitDimensions.ThreatIndicatorSize
            )
        }
    }
}

/**
 * Alert level badge component.
 *
 * @param alertLevel The alert severity level
 * @param isDismissed Whether the alert is dismissed
 * @param modifier Modifier for customization
 */
@Composable
fun AlertLevelBadge(
    alertLevel: AlertLevel,
    isDismissed: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = TailBaitShapeTokens.BadgeShape,
        color = if (isDismissed) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            alertLevel.containerColor()
        }
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = TailBaitDimensions.SpacingSM,
                vertical = TailBaitDimensions.SpacingXS
            ),
            horizontalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingXS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = alertLevel.icon,
                contentDescription = null,
                modifier = Modifier.size(TailBaitDimensions.IconSizeSmall),
                tint = if (isDismissed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    alertLevel.contentColor()
                }
            )
            Text(
                text = if (isDismissed) "Dismissed" else alertLevel.displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (isDismissed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    alertLevel.contentColor()
                }
            )
        }
    }
}

/**
 * Threat score circular indicator.
 * Animation reduced to 300ms for minimal motion.
 *
 * @param threatScore Score from 0.0 to 1.0
 * @param size Size of the indicator
 * @param modifier Modifier for customization
 */
@Composable
fun ThreatScoreIndicator(
    threatScore: Double,
    size: Dp = TailBaitDimensions.ThreatIndicatorSizeLarge,
    modifier: Modifier = Modifier
) {
    val animatedProgress = remember { Animatable(0f) }

    LaunchedEffect(threatScore) {
        animatedProgress.animateTo(
            targetValue = threatScore.toFloat(),
            animationSpec = tween(
                durationMillis = 300, // Reduced from 1000ms for minimal motion
                easing = FastOutSlowInEasing
            )
        )
    }

    val color = when {
        threatScore >= 0.75 -> AlertColors.Critical
        threatScore >= 0.50 -> AlertColors.High
        threatScore >= 0.25 -> AlertColors.Medium
        else -> AlertColors.Low
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.toPx() * 0.12f // Slightly thinner stroke

            // Background circle
            drawCircle(
                color = color.copy(alpha = 0.15f),
                radius = (size.toPx() - strokeWidth) / 2,
                style = Stroke(width = strokeWidth)
            )

            // Progress arc
            val sweepAngle = animatedProgress.value * 360f
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(size.toPx() - strokeWidth, size.toPx() - strokeWidth)
            )
        }

        // Score text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${(threatScore * 100).toInt()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = "score",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Filter chip for alert levels.
 *
 * @param alertLevel The alert level
 * @param selected Whether this chip is selected
 * @param onClick Callback when clicked
 * @param modifier Modifier for customization
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertFilterChip(
    alertLevel: AlertLevel?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(alertLevel?.displayName ?: "All")
        },
        leadingIcon = if (alertLevel != null) {
            {
                Icon(
                    imageVector = alertLevel.icon,
                    contentDescription = null,
                    modifier = Modifier.size(TailBaitDimensions.IconSizeSmall)
                )
            }
        } else null,
        shape = TailBaitShapeTokens.ChipShape,
        modifier = modifier
    )
}

/**
 * Threat score breakdown chart showing contributing factors.
 *
 * @param locationScore Score from location count (0.0 to 1.0)
 * @param distanceScore Score from distance factor (0.0 to 1.0)
 * @param timeScore Score from time correlation (0.0 to 1.0)
 * @param consistencyScore Score from consistency (0.0 to 1.0)
 * @param deviceTypeScore Score from device type (0.0 to 1.0)
 * @param modifier Modifier for customization
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreatScoreBreakdown(
    locationScore: Double,
    distanceScore: Double,
    timeScore: Double,
    consistencyScore: Double,
    deviceTypeScore: Double,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = TailBaitDimensions.ElevationNone
        ),
        shape = TailBaitShapeTokens.CardShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TailBaitDimensions.CardPadding),
            verticalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingMD)
        ) {
            Text(
                text = "Threat Score Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            ScoreFactorBar(
                label = "Location Count",
                score = locationScore,
                weight = 0.3,
                icon = Icons.Outlined.LocationOn
            )

            ScoreFactorBar(
                label = "Distance Factor",
                score = distanceScore,
                weight = 0.25,
                icon = Icons.Outlined.SocialDistance
            )

            ScoreFactorBar(
                label = "Time Correlation",
                score = timeScore,
                weight = 0.2,
                icon = Icons.Outlined.Schedule
            )

            ScoreFactorBar(
                label = "Consistency",
                score = consistencyScore,
                weight = 0.15,
                icon = Icons.Outlined.Timeline
            )

            ScoreFactorBar(
                label = "Device Type",
                score = deviceTypeScore,
                weight = 0.1,
                icon = Icons.Outlined.PhoneAndroid
            )
        }
    }
}

/**
 * Individual score factor bar.
 */
@Composable
private fun ScoreFactorBar(
    label: String,
    score: Double,
    weight: Double,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingXS)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(TailBaitDimensions.IconSizeSmall),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "${(score * weight * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXS))

        LinearProgressIndicator(
            progress = { (score * weight).toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(TailBaitDimensions.ProgressBarHeight)
                .clip(TailBaitShapes.extraSmall),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

/**
 * Device info card for involved devices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvolvedDeviceCard(
    deviceAddress: String,
    deviceName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        ),

        shape = TailBaitShapeTokens.CardShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TailBaitDimensions.ListItemPaddingHorizontal, TailBaitDimensions.ListItemPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingMD)
            ) {
                Box(
                    modifier = Modifier
                        .size(TailBaitDimensions.AvatarSizeMedium)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(TailBaitDimensions.IconSizeMedium),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Column {
                    Text(
                        text = deviceName ?: "Unknown Device",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = deviceAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Icon(
                imageVector = Icons.Filled.ArrowForward,
                contentDescription = "View details",
                modifier = Modifier.size(TailBaitDimensions.IconSizeMedium),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Helper function to format relative time.
 */
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> {
            val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

/**
 * Helper function to parse device count from JSON array string.
 */
private fun parseDeviceCount(deviceAddresses: String): Int {
    return try {
        // Parse JSON array string to count devices
        val cleaned = deviceAddresses.trim()
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned.substring(1, cleaned.length - 1)
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
                .size
        } else {
            0
        }
    } catch (e: Exception) {
        0
    }
}

// ==================== Previews ====================

@Preview(name = "Alert Card - Critical", showBackground = true)
@Composable
private fun AlertCardCriticalPreview() {
    TailBaitTheme {
        AlertCard(
            alert = AlertHistory(
                id = 1,
                alertLevel = "CRITICAL",
                title = "Potential Stalking Device Detected",
                message = "A device has been detected following you across multiple locations.",
                timestamp = System.currentTimeMillis() - 1800000,
                deviceAddresses = "[\"AA:BB:CC:DD:EE:FF\"]",
                locationIds = "[1, 2, 3, 4, 5]",
                threatScore = 0.92,
                detectionDetails = "{}",
                isDismissed = false
            ),
            onClick = {}
        )
    }
}

@Preview(name = "Alert Card - Dismissed", showBackground = true)
@Composable
private fun AlertCardDismissedPreview() {
    TailBaitTheme {
        AlertCard(
            alert = AlertHistory(
                id = 2,
                alertLevel = "HIGH",
                title = "Suspicious Device Activity",
                message = "Multiple devices detected at unusual times.",
                timestamp = System.currentTimeMillis() - 7200000,
                deviceAddresses = "[\"11:22:33:44:55:66\", \"AA:BB:CC:DD:EE:FF\"]",
                locationIds = "[1, 2, 3]",
                threatScore = 0.75,
                detectionDetails = "{}",
                isDismissed = true,
                dismissedAt = System.currentTimeMillis()
            ),
            onClick = {}
        )
    }
}

@Preview(name = "Alert Level Badges", showBackground = true)
@Composable
private fun AlertLevelBadgesPreview() {
    TailBaitTheme {
        Column(
            modifier = Modifier.padding(TailBaitDimensions.SpacingLG),
            verticalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingSM)
        ) {
            AlertLevelBadge(alertLevel = AlertLevel.LOW)
            AlertLevelBadge(alertLevel = AlertLevel.MEDIUM)
            AlertLevelBadge(alertLevel = AlertLevel.HIGH)
            AlertLevelBadge(alertLevel = AlertLevel.CRITICAL)
            AlertLevelBadge(alertLevel = AlertLevel.CRITICAL, isDismissed = true)
        }
    }
}

@Preview(name = "Threat Score Indicator", showBackground = true)
@Composable
private fun ThreatScoreIndicatorPreview() {
    TailBaitTheme {
        Row(
            modifier = Modifier.padding(TailBaitDimensions.SpacingLG),
            horizontalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingLG)
        ) {
            ThreatScoreIndicator(threatScore = 0.20, size = 60.dp)
            ThreatScoreIndicator(threatScore = 0.40, size = 60.dp)
            ThreatScoreIndicator(threatScore = 0.65, size = 60.dp)
            ThreatScoreIndicator(threatScore = 0.90, size = 60.dp)
        }
    }
}

@Preview(name = "Alert Filter Chips", showBackground = true)
@Composable
private fun AlertFilterChipsPreview() {
    TailBaitTheme {
        Row(
            modifier = Modifier.padding(TailBaitDimensions.SpacingLG),
            horizontalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingSM)
        ) {
            AlertFilterChip(alertLevel = null, selected = true, onClick = {})
            AlertFilterChip(alertLevel = AlertLevel.LOW, selected = false, onClick = {})
            AlertFilterChip(alertLevel = AlertLevel.CRITICAL, selected = false, onClick = {})
        }
    }
}

@Preview(name = "Threat Score Breakdown", showBackground = true)
@Composable
private fun ThreatScoreBreakdownPreview() {
    TailBaitTheme {
        ThreatScoreBreakdown(
            locationScore = 0.9,
            distanceScore = 0.8,
            timeScore = 0.7,
            consistencyScore = 0.6,
            deviceTypeScore = 0.5
        )
    }
}

@Preview(name = "Involved Device Card", showBackground = true)
@Composable
private fun InvolvedDeviceCardPreview() {
    TailBaitTheme {
        Column(
            modifier = Modifier.padding(TailBaitDimensions.SpacingLG),
            verticalArrangement = Arrangement.spacedBy(TailBaitDimensions.SpacingSM)
        ) {
            InvolvedDeviceCard(
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                deviceName = "AirTag",
                onClick = {}
            )
            InvolvedDeviceCard(
                deviceAddress = "11:22:33:44:55:66",
                deviceName = null,
                onClick = {}
            )
        }
    }
}
