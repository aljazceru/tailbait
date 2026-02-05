package com.tailbait.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tailbait.ui.screens.settings.SettingsViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tailbait.R
import com.tailbait.ui.screens.alert.AlertDetailScreen
import com.tailbait.ui.screens.alert.AlertScreen
import com.tailbait.ui.screens.devicelist.DeviceListScreen
import com.tailbait.ui.screens.help.HelpScreen
import com.tailbait.ui.screens.home.HomeScreen
import com.tailbait.ui.screens.learnmode.LearnModeScreen
import com.tailbait.ui.screens.map.MapScreen
import com.tailbait.ui.screens.onboarding.OnboardingScreen
import com.tailbait.ui.screens.permissions.PermissionRequestScreen
import com.tailbait.ui.screens.settings.SettingsScreen
import com.tailbait.ui.screens.whitelist.WhitelistScreen

/**
 * Defines the navigation graph for the entire application.
 *
 * This NavHost is the central hub for all screen navigation. It maps routes
 * (string-based paths) to their corresponding Composable screens and handles
 * transitions between them.
 *
 * Key Features:
 * - **Centralized Navigation**: All navigation logic is defined in one place,
 *   making it easy to manage and update the app's flow.
 * - **Type-Safe Arguments**: Uses `navArgument` to pass data between screens with
 *   type safety, reducing runtime errors.
 * - **Deep Linking**: Routes can be configured to handle deep links from notifications
 *   or external sources.
 * - **Modular Structure**: Each screen is a self-contained Composable, promoting
 *   code reusability and separation of concerns.
 *
 * Routes:
 * - `onboarding`: Initial setup and permissions screen.
 * - `home`: Main dashboard with at-a-glance information.
 * - `device_list`: Shows all detected BLE devices.
 * - `map`: Displays device locations on a Google Map.
 * - `alerts`: Lists all generated security alerts.
 * - `alert_detail/{alertId}`: Shows details for a specific alert.
 * - `whitelist`: Manages the list of trusted (whitelisted) devices.
 * - `settings`: Application settings and preferences.
 * - `help`: Provides user guidance and FAQs.
 * - `learn_mode`: A guided process for whitelisting user's own devices.
 *
 * Usage in MainActivity:
 * ```kotlin
 * val navController = rememberNavController()
 * Scaffold(...) {
 *     NavigationGraph(navController = navController)
 * }
 * ```
 *
 * Navigation between screens:
 * ```kotlin
 * navController.navigate(Screen.DeviceList.route)
 * navController.navigate("${Screen.AlertDetail.route}/123") // With argument
 * navController.navigateUp() // Go back
 * ```
 *
 * @param navController The NavController that manages navigation.
 * @param startDestination The initial screen to display.
 */
@Composable
fun NavigationGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(navController = navController, startDestination = startDestination) {
        // Onboarding Screen
        composable(Screen.Onboarding.route) {
            val context = LocalContext.current
            OnboardingScreen(
                onComplete = {
                    // Mark first launch as complete immediately
                    SettingsViewModel.markFirstLaunchComplete(context)
                    navController.navigate(Screen.Permissions.route)
                }
            )
        }

        // Permissions Screen
        composable(Screen.Permissions.route) {
            PermissionRequestScreen(
                onPermissionsGranted = { navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                }}
            )
        }

        // Main Dashboard
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToDeviceList = { navController.navigate(Screen.DeviceList.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToPermissions = { navController.navigate(Screen.Permissions.route) },
                onNavigateToAlerts = { navController.navigate(Screen.Alerts.route) },
                onNavigateToMap = { navController.navigate(Screen.Map.route) }
            )
        }

        // Device List Screen
        composable(Screen.DeviceList.route) {
            DeviceListScreen(
                onNavigateBack = { navController.navigateUp() },
                onDeviceClick = { /* TODO */ },
                onNavigateToMap = { navController.navigate(Screen.Map.route) },
                onNavigateToMapForDevice = { deviceId ->
                    navController.navigate(Screen.Map.createRoute(deviceId))
                }
            )
        }

        // Map Screen (with optional device ID filter)
        composable(
            route = Screen.Map.routeWithArgs,
            arguments = listOf(
                navArgument(Screen.Map.DEVICE_ID_ARG) {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getLong(Screen.Map.DEVICE_ID_ARG)
                ?.takeIf { it != -1L }
            MapScreen(
                onNavigateBack = { navController.navigateUp() },
                initialDeviceId = deviceId
            )
        }

        // Alerts Screen
        composable(Screen.Alerts.route) {
            AlertScreen(navController = navController)
        }

        // Alert Detail Screen
        composable(
            route = "${Screen.AlertDetail.route}/{alertId}",
            arguments = listOf(navArgument("alertId") { type = NavType.LongType })
        ) {
            AlertDetailScreen(navController = navController)
        }

        // Whitelist Screen
        composable(Screen.Whitelist.route) {
            WhitelistScreen(onNavigateBack = { navController.navigateUp() })
        }

        // Settings Screen
        composable(Screen.Settings.route) {
            SettingsScreen(onNavigateBack = { navController.navigateUp() })
        }

        // Help Screen
        composable(Screen.Help.route) {
            HelpScreen(onNavigateBack = { navController.navigateUp() })
        }

        // Learn Mode Screen
        composable(Screen.LearnMode.route) {
            LearnModeScreen(onNavigateBack = { navController.navigateUp() })
        }
    }
}

/**
 * A generic top app bar with a title and back button.
 *
 * This is a reusable app bar for screens that are not on the main navigation level.
 * It provides a consistent back navigation experience.
 *
 * @param title The title to display in the app bar.
 * @param onBackClick The action to perform when the back button is clicked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBackClick: () -> Unit) {
    TopAppBar(
        title = { Text(text = title) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back_button_desc)
                )
            }
        }
    )
}
