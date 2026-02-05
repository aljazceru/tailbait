package com.tailbait

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.service.TailBaitService
import com.tailbait.ui.navigation.NavigationGraph
import timber.log.Timber
import com.tailbait.ui.navigation.Screen
import com.tailbait.ui.screens.settings.SettingsViewModel
import com.tailbait.ui.theme.TailBaitTheme
import com.tailbait.util.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main Activity for BLE Tracker App
 *
 * This activity serves as the entry point for the application.
 * It uses Jetpack Compose for UI and Hilt for dependency injection.
 *
 * The activity sets up the navigation graph and provides the theme wrapper
 * for the entire app. All screens are navigable through the NavigationGraph.
 *
 * On first launch, the app shows the onboarding screen to introduce
 * users to key features and explain permissions.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Auto-start tracking service if tracking was enabled
        restoreTrackingServiceIfNeeded()

        setContent {
            // Observe theme mode from settings
            val themeMode by settingsRepository.getThemeMode().collectAsState(initial = AppSettings.THEME_SYSTEM)

            // Determine dark theme based on user preference
            val darkTheme = when (themeMode) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_DARK -> true
                else -> isSystemInDarkTheme() // SYSTEM or unknown defaults to system
            }

            TailBaitTheme(darkTheme = darkTheme) {
                BLETrackerApp()
            }
        }
    }

    /**
     * Restore the tracking service if tracking was enabled before app restart.
     * Only restores if location permissions are granted (required for foreground service
     * with location type on Android 14+).
     */
    private fun restoreTrackingServiceIfNeeded() {
        lifecycleScope.launch {
            try {
                val settings = settingsRepository.getSettingsOnce()
                val permissionHelper = PermissionHelper(this@MainActivity)

                if (settings.isTrackingEnabled && permissionHelper.hasLocationPermissions()) {
                    Timber.i("Restoring tracking service (tracking was enabled)")
                    val intent = Intent(this@MainActivity, TailBaitService::class.java).apply {
                        action = TailBaitService.ACTION_START_TRACKING
                    }
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                } else if (settings.isTrackingEnabled) {
                    Timber.w("Cannot restore tracking: location permissions not granted")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to restore tracking service")
            }
        }
    }

    /**
     * Override dispatchTouchEvent to prevent hover event crashes
     * This is a known issue with certain Android versions and OEM builds
     * where ACTION_HOVER_EXIT events are not properly cleared by Compose
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        return try {
            // Handle hover events to prevent Compose crashes
            when (ev?.action) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    // Clear any pending hover state
                    currentFocus?.clearFocus()
                    super.dispatchTouchEvent(ev)
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    // Ensure hover state is properly cleared
                    currentFocus?.clearFocus()
                    // Return false to prevent event from propagating to Compose
                    false
                }
                MotionEvent.ACTION_HOVER_MOVE -> {
                    // Allow hover movement but be careful with state
                    super.dispatchTouchEvent(ev)
                }
                else -> {
                    super.dispatchTouchEvent(ev)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling touch event, preventing crash")
            // Clear focus and continue to prevent app crash
            try {
                currentFocus?.clearFocus()
            } catch (ignore: Exception) {
                // Ignore secondary exceptions
            }
            true // Consume the problematic event
        }
    }
}

/**
 * Main app composable that sets up navigation.
 *
 * Checks if this is the first launch and shows onboarding if needed.
 * After onboarding is completed or skipped, navigates to the home screen.
 */
@Composable
fun BLETrackerApp() {
    val context = LocalContext.current
    val navController = rememberNavController()

    // Initialize PermissionHelper to check permission states
    val permissionHelper = remember { PermissionHelper(context) }

    // Check if this is the first launch
    val isFirstLaunch = remember {
        SettingsViewModel.isFirstLaunch(context)
    }

    // Track if onboarding has been completed in this session
    var onboardingCompleted by remember { mutableStateOf(false) }

    // Check current permission states
    val hasAllPermissions = remember {
        permissionHelper.areEssentialPermissionsGranted()
    }

    // Determine start destination
    val startDestination = when {
        isFirstLaunch && !onboardingCompleted -> Screen.Onboarding.route
        !hasAllPermissions -> Screen.Permissions.route
        else -> Screen.Home.route
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        NavigationGraph(
            navController = navController,
            startDestination = startDestination
        )
    }

    // Mark first launch as complete when leaving onboarding
    LaunchedEffect(navController.currentBackStackEntry) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        if (currentRoute != Screen.Onboarding.route && isFirstLaunch && !onboardingCompleted) {
            SettingsViewModel.markFirstLaunchComplete(context)
            onboardingCompleted = true
        }
    }
}

/**
 * Preview for the main app
 */
@Preview(showBackground = true, name = "TailBait App")
@Composable
fun TailBaitAppPreview() {
    TailBaitTheme {
        BLETrackerApp()
    }
}
