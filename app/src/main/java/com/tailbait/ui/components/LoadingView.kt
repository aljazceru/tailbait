package com.tailbait.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.tailbait.ui.theme.TailBaitDimensions
import com.tailbait.ui.theme.TailBaitTheme

/**
 * A reusable loading view component.
 *
 * This component displays a loading indicator and an optional message to inform the user
 * that content is being loaded. It supports two styles:
 * - Circular: A circular progress indicator for indeterminate loading.
 * - Linear: A linear progress indicator for determinate or indeterminate loading.
 *
 * @param message The message to display below the loading indicator
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun LoadingView(
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(TailBaitDimensions.SpacingXXXL),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(TailBaitDimensions.IconSizeXL),
                strokeWidth = TailBaitDimensions.ProgressStrokeWidth
            )

            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingLG))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * A compact loading view for inline use within other components.
 *
 * This is a smaller version designed for cards, lists, or sections where
 * a full-screen loading state would be inappropriate.
 *
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun CompactLoadingView(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(TailBaitDimensions.SpacingLG),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(TailBaitDimensions.IconSizeMedium),
            strokeWidth = TailBaitDimensions.ProgressStrokeWidthSmall
        )
    }
}

/**
 * A linear loading view for determinate or indeterminate progress.
 *
 * @param progress Optional progress value (0.0 to 1.0) for determinate loading.
 *                 If null, an indeterminate progress bar is shown.
 * @param modifier Modifier for customizing the layout
 */
@Composable
fun LinearLoadingView(
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress }
            )
        } else {
            LinearProgressIndicator()
        }
    }
}

// Preview functions for design-time visualization
@Preview(name = "Loading View - Basic", showBackground = true)
@Composable
private fun LoadingViewBasicPreview() {
    TailBaitTheme {
        Surface {
            LoadingView(message = "Fetching device data...")
        }
    }
}

@Preview(name = "Compact Loading View", showBackground = true)
@Composable
private fun CompactLoadingViewPreview() {
    TailBaitTheme {
        Surface(modifier = Modifier.size(TailBaitDimensions.CardMinHeight)) {
            CompactLoadingView()
        }
    }
}

@Preview(name = "Linear Loading View - Determinate", showBackground = true)
@Composable
private fun LinearLoadingViewDeterminatePreview() {
    TailBaitTheme {
        Surface {
            LinearLoadingView(
                progress = 0.75f,
                modifier = Modifier.padding(TailBaitDimensions.SpacingLG)
            )
        }
    }
}

@Preview(name = "Linear Loading View - Indeterminate", showBackground = true)
@Composable
private fun LinearLoadingViewIndeterminatePreview() {
    TailBaitTheme {
        Surface {
            LinearLoadingView(modifier = Modifier.padding(TailBaitDimensions.SpacingLG))
        }
    }
}

@Preview(name = "Loading View - Dark Mode", showBackground = true)
@Composable
private fun LoadingViewDarkPreview() {
    TailBaitTheme(darkTheme = true) {
        Surface {
            LoadingView(message = "Loading...")
        }
    }
}
