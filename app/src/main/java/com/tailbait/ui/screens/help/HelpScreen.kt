package com.tailbait.ui.screens.help

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Help Screen.
 *
 * Provides comprehensive help content including:
 * - FAQ section with common questions and answers
 * - Feature tutorials explaining how to use the app
 * - Contact/feedback options for user support
 *
 * Uses expandable cards for a clean, organized layout.
 *
 * @param onNavigateBack Callback when back button is clicked
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help & Support") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Introduction card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Learn how to use TailBait to protect yourself from unwanted tracking.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Getting Started section
            SectionHeader(
                title = "Getting Started",
                icon = Icons.Outlined.RocketLaunch
            )

            ExpandableHelpItem(
                title = "How does BLE tracking work?",
                icon = Icons.Outlined.BluetoothSearching
            ) {
                Text(
                    text = "The app continuously scans for nearby Bluetooth Low Energy (BLE) devices. " +
                            "It tracks each device's location over time and analyzes patterns to detect " +
                            "if a device is following you. When suspicious patterns are detected, you'll " +
                            "receive an alert.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            ExpandableHelpItem(
                title = "What permissions are needed?",
                icon = Icons.Outlined.Security
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Required Permissions:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    PermissionExplanation(
                        name = "Bluetooth",
                        reason = "To scan for nearby BLE devices"
                    )
                    PermissionExplanation(
                        name = "Location",
                        reason = "To track where devices are detected"
                    )
                    PermissionExplanation(
                        name = "Notifications",
                        reason = "To alert you of suspicious devices"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Optional Permissions:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    PermissionExplanation(
                        name = "Background Location",
                        reason = "To continue tracking when the app is not active"
                    )
                }
            }

            ExpandableHelpItem(
                title = "How to start tracking?",
                icon = Icons.Outlined.PlayCircle
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "1. Grant all required permissions",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. Tap the 'Start Tracking' button on the home screen",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. Keep the app running in the background",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "4. You'll receive alerts if suspicious devices are detected",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Features section
            SectionHeader(
                title = "Features",
                icon = Icons.Outlined.AutoAwesome
            )

            ExpandableHelpItem(
                title = "Tracking Modes",
                icon = Icons.Outlined.Tune
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TrackingModeExplanation(
                        name = "Continuous",
                        description = "Constantly scans for devices. Highest accuracy but uses more battery."
                    )
                    TrackingModeExplanation(
                        name = "Periodic",
                        description = "Scans at regular intervals. Balanced accuracy and battery usage."
                    )
                    TrackingModeExplanation(
                        name = "Location-Based",
                        description = "Scans when you move to a new location. Most battery efficient."
                    )
                }
            }

            ExpandableHelpItem(
                title = "Understanding Alerts",
                icon = Icons.Outlined.NotificationsActive
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Alerts are triggered when a device is detected at multiple locations with you. " +
                                "The severity depends on:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "- Number of distinct locations",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "- Distance between locations",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "- Time span of detections",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "- Pattern consistency",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            ExpandableHelpItem(
                title = "Whitelist Feature",
                icon = Icons.Outlined.CheckCircle
            ) {
                Text(
                    text = "Add your own devices (AirPods, smartwatch, etc.) to the whitelist " +
                            "to prevent false alerts. Whitelisted devices are still tracked but " +
                            "won't trigger alerts. You can access the whitelist from the device " +
                            "list or Learn Mode.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            ExpandableHelpItem(
                title = "Learn Mode",
                icon = Icons.Outlined.School
            ) {
                Text(
                    text = "Learn Mode helps you identify your personal devices. Activate it in " +
                            "a safe environment (like your home), and all detected devices will be " +
                            "shown. You can then add your devices to the whitelist to avoid false alerts.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // FAQ section
            SectionHeader(
                title = "FAQ",
                icon = Icons.Outlined.HelpOutline
            )

            ExpandableHelpItem(
                title = "Why am I getting false alerts?",
                icon = Icons.Outlined.ErrorOutline
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "False alerts can occur due to:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "- Your own devices not added to whitelist",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "- Devices from people you travel with regularly",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "- Public devices (buses, trains) that move with you",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Solution: Use Learn Mode to identify and whitelist your devices. " +
                                "Adjust alert thresholds in Settings if needed.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            ExpandableHelpItem(
                title = "Does the app drain my battery?",
                icon = Icons.Outlined.BatteryAlert
            ) {
                Text(
                    text = "The app is optimized for battery efficiency. Use 'Periodic' or " +
                            "'Location-Based' modes for better battery life. Enable 'Battery " +
                            "Optimization' in Settings to reduce scan frequency and duration. " +
                            "You can also adjust scan intervals to balance detection accuracy " +
                            "with battery usage.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            ExpandableHelpItem(
                title = "Is my data private?",
                icon = Icons.Outlined.PrivacyTip
            ) {
                Text(
                    text = "Yes! All data is stored locally on your device. The app does not " +
                            "collect, transmit, or share any information with external servers. " +
                            "Your location history, device detections, and alerts remain completely " +
                            "private and under your control.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            ExpandableHelpItem(
                title = "What should I do if I detect a tracker?",
                icon = Icons.Outlined.Warning
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "If you receive a high-priority alert:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "1. Stay calm and don't panic",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. Check the alert details and location history",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. Verify it's not a known device or false positive",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "4. Look for the physical device if safe to do so",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "5. Contact local authorities if you feel unsafe",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "6. Document the device information for evidence",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Contact section
            SectionHeader(
                title = "Contact & Feedback",
                icon = Icons.Outlined.ContactSupport
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Need more help?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "We're here to help. If you have questions, suggestions, or " +
                                "need support, please reach out to us.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { },
                            modifier = Modifier.weight(1f),
                            enabled = false
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Email,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Email")
                        }
                        OutlinedButton(
                            onClick = { },
                            modifier = Modifier.weight(1f),
                            enabled = false
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.BugReport,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Report Bug")
                        }
                    }
                }
            }

            // Bottom spacing
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Section header component.
 */
@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Expandable help item with collapsible content.
 */
@Composable
private fun ExpandableHelpItem(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header (always visible)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.ExpandLess
                        } else {
                            Icons.Default.ExpandMore
                        },
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expandable content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 52.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Permission explanation component.
 */
@Composable
private fun PermissionExplanation(
    name: String,
    reason: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp)
        )
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Tracking mode explanation component.
 */
@Composable
private fun TrackingModeExplanation(
    name: String,
    description: String
) {
    Column {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
