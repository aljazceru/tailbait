package com.tailbait.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailbait.ui.theme.TailBaitTheme

/**
 * A reusable key-value display component for showing labeled data.
 *
 * This component displays information in a key-value format where:
 * - The key (label) is shown on the left in a muted color
 * - The value is shown on the right with emphasis
 * - Optional icon can be displayed next to the key
 *
 * Commonly used for:
 * - Device information displays (MAC address, RSSI, etc.)
 * - Settings values
 * - Alert details
 * - Location information
 *
 * @param label The key/label text
 * @param value The value text to display
 * @param modifier Modifier for customizing the layout
 * @param icon Optional icon to display next to the label
 * @param valueColor Optional custom color for the value text
 * @param monospaceValue Whether to use monospace font for the value (useful for addresses/IDs)
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    valueColor: Color? = null,
    monospaceValue: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Label with optional icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Value
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Normal,
            fontFamily = if (monospaceValue) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

/**
 * A column-based key-value display component.
 *
 * This variant displays the key above the value, useful when:
 * - Values are long and need more horizontal space
 * - Creating a more compact vertical layout
 * - Displaying multi-line values
 *
 * @param label The key/label text
 * @param value The value text to display
 * @param modifier Modifier for customizing the layout
 * @param valueColor Optional custom color for the value text
 * @param monospaceValue Whether to use monospace font for the value
 */
@Composable
fun KeyValueColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    monospaceValue: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Normal,
            fontFamily = if (monospaceValue) FontFamily.Monospace else FontFamily.Default
        )
    }
}

/**
 * A compact key-value display component for inline use.
 *
 * This is a smaller, more condensed version for use in:
 * - List items
 * - Cards
 * - Dense information displays
 *
 * @param label The key/label text
 * @param value The value text to display
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun CompactKeyValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * A collection of multiple key-value pairs displayed in a column.
 *
 * This is a convenience component for displaying multiple related
 * key-value pairs together.
 *
 * @param items List of Pair<String, String> where first is label and second is value
 * @param modifier Modifier for customizing the layout
 * @param useColumnLayout Whether to use column layout instead of row (default: false)
 */
@Composable
fun KeyValueGroup(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    useColumnLayout: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (label, value) ->
            if (useColumnLayout) {
                KeyValueColumn(label = label, value = value)
            } else {
                KeyValueRow(label = label, value = value)
            }
        }
    }
}

// Preview functions for design-time visualization
@Preview(name = "Key-Value Row - Basic", showBackground = true)
@Composable
private fun KeyValueRowBasicPreview() {
    TailBaitTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                KeyValueRow(
                    label = "Device Name",
                    value = "AirPods Pro"
                )
            }
        }
    }
}

@Preview(name = "Key-Value Row - With Icon", showBackground = true)
@Composable
private fun KeyValueRowWithIconPreview() {
    TailBaitTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                KeyValueRow(
                    label = "Status",
                    value = "Active",
                    icon = Icons.Outlined.Info,
                    valueColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Preview(name = "Key-Value Row - Monospace", showBackground = true)
@Composable
private fun KeyValueRowMonospacePreview() {
    TailBaitTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                KeyValueRow(
                    label = "MAC Address",
                    value = "AA:BB:CC:DD:EE:FF",
                    monospaceValue = true
                )
            }
        }
    }
}

@Preview(name = "Key-Value Column", showBackground = true)
@Composable
private fun KeyValueColumnPreview() {
    TailBaitTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                KeyValueColumn(
                    label = "Detection Message",
                    value = "This device has been detected at 5 different locations over the past 3 days"
                )
            }
        }
    }
}

@Preview(name = "Compact Key-Value", showBackground = true)
@Composable
private fun CompactKeyValuePreview() {
    TailBaitTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                CompactKeyValue(label = "RSSI", value = "-65 dBm")
                CompactKeyValue(label = "Distance", value = "~2m")
            }
        }
    }
}

@Preview(name = "Key-Value Group - Row Layout", showBackground = true)
@Composable
private fun KeyValueGroupRowPreview() {
    TailBaitTheme {
        Surface {
            KeyValueGroup(
                items = listOf(
                    "Device Type" to "Smartphone",
                    "First Seen" to "2 hours ago",
                    "Last Seen" to "5 minutes ago",
                    "Detection Count" to "12 times"
                ),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(name = "Key-Value Group - Column Layout", showBackground = true)
@Composable
private fun KeyValueGroupColumnPreview() {
    TailBaitTheme {
        Surface {
            KeyValueGroup(
                items = listOf(
                    "Threat Score" to "0.87 (High Risk)",
                    "Locations" to "5 distinct locations",
                    "Max Distance" to "15.3 km"
                ),
                modifier = Modifier.padding(16.dp),
                useColumnLayout = true
            )
        }
    }
}

@Preview(name = "Device Info Card Example", showBackground = true)
@Composable
private fun DeviceInfoCardPreview() {
    TailBaitTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Device Information",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                KeyValueRow(
                    label = "Name",
                    value = "Unknown Device"
                )
                KeyValueRow(
                    label = "Address",
                    value = "12:34:56:78:9A:BC",
                    monospaceValue = true
                )
                KeyValueRow(
                    label = "RSSI",
                    value = "-72 dBm"
                )
                KeyValueRow(
                    label = "First Detected",
                    value = "Jan 15, 2025 at 14:30"
                )
                KeyValueRow(
                    label = "Status",
                    value = "Suspicious",
                    valueColor = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Preview(name = "Key-Value Dark Mode", showBackground = true)
@Composable
private fun KeyValueDarkPreview() {
    TailBaitTheme(darkTheme = true) {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                KeyValueRow(
                    label = "Device Name",
                    value = "AirPods Pro"
                )
                KeyValueRow(
                    label = "MAC Address",
                    value = "AA:BB:CC:DD:EE:FF",
                    monospaceValue = true
                )
            }
        }
    }
}
