package com.tailbait.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailbait.ui.theme.TailBaitTheme

/**
 * A reusable section title component with a horizontal divider.
 *
 * This component provides a consistent section title style for use in:
 * - Settings screens
 * - Detail screens
 * - Home screen sections
 *
 * @param title The title text to display
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Divider(modifier = Modifier.weight(1f))
    }
}

// Preview functions

@Preview(showBackground = true)
@Composable
private fun SectionTitlePreview() {
    TailBaitTheme {
        Surface {
            SectionTitle(title = "General Settings")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SectionTitleDarkPreview() {
    TailBaitTheme(darkTheme = true) {
        Surface {
            SectionTitle(title = "Device Details")
        }
    }
}
