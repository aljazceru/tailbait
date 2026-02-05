package com.tailbait.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class to manage runtime permissions for TailBait.
 * Handles permission differences across Android 8-15 (API 26-35).
 *
 * Required Permissions:
 * - Bluetooth (BLUETOOTH_SCAN, BLUETOOTH_CONNECT for API 31+)
 * - Location (ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION)
 * - Notifications (POST_NOTIFICATIONS for API 33+)
 */
@Singleton
class PermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // State flows for reactive permission state updates
    private val _bluetoothPermissionsState = MutableStateFlow(PermissionState.UNKNOWN)
    val bluetoothPermissionsState: StateFlow<PermissionState> = _bluetoothPermissionsState.asStateFlow()

    private val _locationPermissionsState = MutableStateFlow(PermissionState.UNKNOWN)
    val locationPermissionsState: StateFlow<PermissionState> = _locationPermissionsState.asStateFlow()

    private val _backgroundLocationPermissionState = MutableStateFlow(PermissionState.UNKNOWN)
    val backgroundLocationPermissionState: StateFlow<PermissionState> = _backgroundLocationPermissionState.asStateFlow()

    private val _notificationPermissionState = MutableStateFlow(PermissionState.UNKNOWN)
    val notificationPermissionState: StateFlow<PermissionState> = _notificationPermissionState.asStateFlow()

    private val _allPermissionsGrantedState = MutableStateFlow(false)
    val allPermissionsGrantedState: StateFlow<Boolean> = _allPermissionsGrantedState.asStateFlow()

    enum class PermissionState {
        UNKNOWN,
        GRANTED,
        DENIED,
        PERMANENTLY_DENIED
    }

    /**
     * Permission groups with their rationale
     */
    enum class PermissionGroup {
        BLUETOOTH,
        LOCATION,
        BACKGROUND_LOCATION,
        NOTIFICATIONS
    }

    /**
     * Initialize and check all permissions
     */
    fun checkAllPermissions() {
        _bluetoothPermissionsState.value = checkBluetoothPermissions()
        _locationPermissionsState.value = checkLocationPermissions()
        _backgroundLocationPermissionState.value = checkBackgroundLocationPermission()
        _notificationPermissionState.value = checkNotificationPermission()
        _allPermissionsGrantedState.value = areAllPermissionsGranted()
    }

    /**
     * Update permission state after permission request
     */
    fun updatePermissionStates() {
        checkAllPermissions()
    }

    // ==================== Bluetooth Permissions ====================

    /**
     * Get required Bluetooth permissions based on Android version
     */
    fun getRequiredBluetoothPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            // Android 11 and below (API 30 and below)
            // BLUETOOTH and BLUETOOTH_ADMIN are not dangerous permissions
            // but still declared in manifest
            emptyList()
        }
    }

    /**
     * Check if Bluetooth permissions are granted
     */
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                    checkPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // On Android 11 and below, Bluetooth permissions are not runtime permissions
            true
        }
    }

    /**
     * Check Bluetooth permission state
     */
    private fun checkBluetoothPermissions(): PermissionState {
        return if (hasBluetoothPermissions()) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED
        }
    }

    // ==================== Location Permissions ====================

    /**
     * Get required foreground location permissions
     */
    fun getRequiredLocationPermissions(): List<String> {
        return listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    /**
     * Check if foreground location permissions are granted
     */
    fun hasLocationPermissions(): Boolean {
        return checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Check location permission state
     */
    private fun checkLocationPermissions(): PermissionState {
        return if (hasLocationPermissions()) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED
        }
    }

    // ==================== Background Location Permission ====================

    /**
     * Get background location permission (Android 10+)
     */
    fun getBackgroundLocationPermission(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else {
            null // Not required on Android 9 and below
        }
    }

    /**
     * Check if background location permission is granted
     */
    fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            // On Android 9 and below, background location is automatically granted
            // if foreground location is granted
            true
        }
    }

    /**
     * Check if background location permission is required
     */
    fun isBackgroundLocationPermissionRequired(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /**
     * Check background location permission state
     */
    private fun checkBackgroundLocationPermission(): PermissionState {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (hasBackgroundLocationPermission()) {
                PermissionState.GRANTED
            } else {
                PermissionState.DENIED
            }
        } else {
            PermissionState.GRANTED // Not required on older versions
        }
    }

    // ==================== Notification Permission ====================

    /**
     * Get notification permission (Android 13+)
     */
    fun getNotificationPermission(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null // Not required on Android 12 and below
        }
    }

    /**
     * Check if notification permission is granted
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // On Android 12 and below, notification permission is not required
            true
        }
    }

    /**
     * Check if notification permission is required
     */
    fun isNotificationPermissionRequired(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Check notification permission state
     */
    private fun checkNotificationPermission(): PermissionState {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (hasNotificationPermission()) {
                PermissionState.GRANTED
            } else {
                PermissionState.DENIED
            }
        } else {
            PermissionState.GRANTED // Not required on older versions
        }
    }

    // ==================== Combined Permission Checks ====================

    /**
     * Check if all required permissions are granted
     */
    fun areAllPermissionsGranted(): Boolean {
        return hasBluetoothPermissions() &&
                hasLocationPermissions() &&
                hasBackgroundLocationPermission() &&
                hasNotificationPermission()
    }

    /**
     * Check if all essential permissions (excluding background location) are granted
     */
    fun areEssentialPermissionsGranted(): Boolean {
        return hasBluetoothPermissions() &&
                hasLocationPermissions() &&
                hasNotificationPermission()
    }

    /**
     * Get all required permissions for the current Android version
     */
    fun getAllRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        // Bluetooth permissions (Android 12+)
        permissions.addAll(getRequiredBluetoothPermissions())

        // Location permissions (all versions)
        permissions.addAll(getRequiredLocationPermissions())

        // Notification permission (Android 13+)
        getNotificationPermission()?.let { permissions.add(it) }

        return permissions
    }

    /**
     * Get all permissions including background location (to be requested separately)
     */
    fun getAllPermissionsIncludingBackground(): List<String> {
        val permissions = getAllRequiredPermissions().toMutableList()

        // Background location (Android 10+) - should be requested separately
        getBackgroundLocationPermission()?.let { permissions.add(it) }

        return permissions
    }

    // ==================== Permission Rationale ====================

    /**
     * Get permission rationale text for a permission group
     */
    fun getPermissionRationale(group: PermissionGroup): String {
        return when (group) {
            PermissionGroup.BLUETOOTH ->
                "Bluetooth permission is required to scan for nearby BLE devices. " +
                "This is essential for detecting devices that may be tracking you."

            PermissionGroup.LOCATION ->
                "Location permission is required to track where devices are detected. " +
                "This helps identify if the same device is following you across different locations."

            PermissionGroup.BACKGROUND_LOCATION ->
                "Background location permission allows the app to track your location " +
                "even when it's not actively being used. This ensures continuous protection " +
                "and can detect tracking devices 24/7. This is optional but highly recommended " +
                "for maximum protection."

            PermissionGroup.NOTIFICATIONS ->
                "Notification permission is required to alert you immediately when a " +
                "potential tracking device is detected. Timely alerts are crucial for your safety."
        }
    }

    /**
     * Get short permission rationale for UI
     */
    fun getShortPermissionRationale(group: PermissionGroup): String {
        return when (group) {
            PermissionGroup.BLUETOOTH -> "Required to scan for BLE devices"
            PermissionGroup.LOCATION -> "Required to track device locations"
            PermissionGroup.BACKGROUND_LOCATION -> "Optional: Enable 24/7 protection"
            PermissionGroup.NOTIFICATIONS -> "Required for instant alerts"
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Check if a specific permission is granted
     */
    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if we should show rationale for a permission
     * Note: This requires Activity context, so it's a utility method
     * that should be called from Activity/Fragment
     */
    fun shouldShowRationaleInfo(permission: String): String {
        return when {
            permission == Manifest.permission.BLUETOOTH_SCAN ||
            permission == Manifest.permission.BLUETOOTH_CONNECT ->
                "Bluetooth permissions are essential for scanning nearby BLE devices."

            permission == Manifest.permission.ACCESS_FINE_LOCATION ||
            permission == Manifest.permission.ACCESS_COARSE_LOCATION ->
                "Location permissions are required to record where devices are detected."

            permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION ->
                "Background location enables 24/7 protection but is optional."

            permission == Manifest.permission.POST_NOTIFICATIONS ->
                "Notification permission is needed to alert you of potential threats."

            else -> "This permission is required for the app to function properly."
        }
    }

    /**
     * Get user-friendly permission name
     */
    fun getPermissionName(permission: String): String {
        return when (permission) {
            Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth Scan"
            Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth Connect"
            Manifest.permission.ACCESS_FINE_LOCATION -> "Fine Location"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "Coarse Location"
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Background Location"
            Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
            else -> permission.substringAfterLast('.')
        }
    }

    companion object {
        /**
         * Permission request codes for different permission groups
         */
        const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
        const val REQUEST_LOCATION_PERMISSIONS = 1002
        const val REQUEST_BACKGROUND_LOCATION_PERMISSION = 1003
        const val REQUEST_NOTIFICATION_PERMISSION = 1004
        const val REQUEST_ALL_PERMISSIONS = 1005
    }
}
