package com.tailbait.ui.navigation

/**
 * Navigation routes for the BLE Tracker app.
 *
 * This object defines all route constants used for navigation throughout the app.
 * Using constants ensures type safety and prevents typos in route strings.
 *
 * Route Hierarchy:
 * - Home: Main dashboard with scanning controls and status
 * - DeviceList: List of all discovered devices with filtering/sorting
 * - DeviceDetails: Detailed view of a specific device
 * - Permissions: Permission request and status screen
 * - Settings: App configuration and preferences
 *
 * Usage:
 * ```
 * navController.navigate(Routes.HOME)
 * navController.navigate(Routes.deviceDetails(deviceId = 123))
 * ```
 */
object Routes {
    /**
     * Home screen route - main dashboard
     */
    const val HOME = "home"

    /**
     * Device list screen route - shows all discovered devices
     */
    const val DEVICE_LIST = "device_list"

    /**
     * Device details screen route with device ID parameter
     * Use deviceDetails() function to construct route with parameter
     */
    const val DEVICE_DETAILS = "device_details/{deviceId}"

    /**
     * Permissions screen route - request and manage permissions
     */
    const val PERMISSIONS = "permissions"

    /**
     * Settings screen route - app configuration
     */
    const val SETTINGS = "settings"

    /**
     * Alert list screen route - shows all alerts
     */
    const val ALERTS = "alerts"

    /**
     * Alert details screen route with alert ID parameter
     * Use alertDetails() function to construct route with parameter
     */
    const val ALERT_DETAILS = "alert_details/{alertId}"

    /**
     * Whitelist management screen route
     */
    const val WHITELIST = "whitelist"

    /**
     * Learn mode screen route
     */
    const val LEARN_MODE = "learn_mode"

    /**
     * Map screen route - shows device locations on map
     */
    const val MAP = "map"

    /**
     * Onboarding screen route - first-time user tutorial
     */
    const val ONBOARDING = "onboarding"

    /**
     * Help screen route - FAQ and tutorials
     */
    const val HELP = "help"

    /**
     * Construct a device details route with the given device ID.
     *
     * @param deviceId The ID of the device to view
     * @return Complete route string with parameter
     */
    fun deviceDetails(deviceId: Long): String = "device_details/$deviceId"

    /**
     * Construct an alert details route with the given alert ID.
     *
     * @param alertId The ID of the alert to view
     * @return Complete route string with parameter
     */
    fun alertDetails(alertId: Long): String = "alert_details/$alertId"
}

/**
 * Navigation argument keys for extracting parameters from routes.
 */
object NavArgs {
    /**
     * Device ID argument key
     */
    const val DEVICE_ID = "deviceId"

    /**
     * Alert ID argument key
     */
    const val ALERT_ID = "alertId"
}
