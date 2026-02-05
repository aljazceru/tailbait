package com.tailbait.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tailbait.ui.theme.TailBaitDimensions
import com.tailbait.ui.theme.TailBaitShapeTokens
import kotlinx.coroutines.launch

/**
 * Onboarding Screen.
 *
 * Displays a multi-page tutorial introducing users to the app's key features:
 * - Page 1: Welcome and app purpose
 * - Page 2: BLE tracking explanation
 * - Page 3: Alert system
 * - Page 4: Privacy and permissions
 *
 * Shown only on first launch, with options to skip or complete the tutorial.
 *
 * @param onComplete Callback when onboarding is completed or skipped
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val pages = listOf(
        OnboardingPage(
            title = "Welcome to TailBait",
            description = "Your personal safety companion for detecting unwanted " +
                "Bluetooth tracking devices around you.",
            icon = Icons.Filled.Shield,
            color = MaterialTheme.colorScheme.primary
        ),
        OnboardingPage(
            title = "Automatic Detection",
            description = "The app continuously scans for nearby Bluetooth devices and " +
                "tracks their locations to identify suspicious patterns.",
            icon = Icons.Outlined.BluetoothSearching,
            color = MaterialTheme.colorScheme.secondary
        ),
        OnboardingPage(
            title = "Smart Alerts",
            description = "Get notified when a device appears at multiple locations " +
                "with you, indicating potential tracking.",
            icon = Icons.Outlined.NotificationsActive,
            color = MaterialTheme.colorScheme.tertiary
        ),
        OnboardingPage(
            title = "Privacy First",
            description = "All data stays on your device. We'll need a few permissions " +
                "to detect devices and track locations.",
            icon = Icons.Outlined.Lock,
            color = MaterialTheme.colorScheme.error
        )
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Skip button (hidden on last page)
            AnimatedVisibility(
                visible = pagerState.currentPage < pages.size - 1,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    TextButton(onClick = onComplete) {
                        Text("Skip")
                    }
                }
            }

            // Page content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                OnboardingPageContent(
                    page = pages[page]
                )
            }

            // Page indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages.size) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (index == pagerState.currentPage) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                }
                            )
                    )
                }
            }

            // Navigation buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TailBaitDimensions.SpacingXXL, vertical = TailBaitDimensions.SpacingXXL),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Back button (hidden on first page)
                if (pagerState.currentPage > 0) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    pagerState.currentPage - 1,
                                    animationSpec = tween(300)
                                )
                            }
                        },
                        shape = TailBaitShapeTokens.ButtonShape
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Previous"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Back")
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                // Next/Get Started button
                Button(
                    onClick = {
                        if (pagerState.currentPage < pages.size - 1) {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    pagerState.currentPage + 1,
                                    animationSpec = tween(300)
                                )
                            }
                        } else {
                            onComplete()
                        }
                    },
                    shape = TailBaitShapeTokens.ButtonShape
                ) {
                    Text(
                        if (pagerState.currentPage < pages.size - 1) {
                            "Next"
                        } else {
                            "Get Started"
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (pagerState.currentPage < pages.size - 1) {
                            Icons.Default.ChevronRight
                        } else {
                            Icons.Default.Check
                        },
                        contentDescription = if (pagerState.currentPage < pages.size - 1) {
                            "Next"
                        } else {
                            "Get Started"
                        }
                    )
                }
            }
        }
    }
}

/**
 * Data class representing an onboarding page.
 */
data class OnboardingPage(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val color: androidx.compose.ui.graphics.Color
)

/**
 * Content for a single onboarding page.
 */
@Composable
private fun OnboardingPageContent(
    page: OnboardingPage
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = TailBaitDimensions.SpacingXXXL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        Surface(
            modifier = Modifier.size(TailBaitDimensions.OnboardingIconSize),
            shape = CircleShape,
            color = page.color.copy(alpha = 0.1f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(TailBaitDimensions.OnboardingIconInnerSize),
                    tint = page.color
                )
            }
        }

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXXXL))

        // Title
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingLG))

        // Description
        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
