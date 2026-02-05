package com.tailbait.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Utility functions for permission-related operations
 */
object PermissionUtils {

    /**
     * Open app settings page where user can manually grant permissions
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Open location settings page
     */
    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Open Bluetooth settings page
     */
    fun openBluetoothSettings(context: Context) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Open notification settings page for the app
     */
    fun openNotificationSettings(context: Context) {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    /**
     * Check if location services are enabled on the device
     */
    fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        return locationManager?.let {
            it.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    it.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        } ?: false
    }

    /**
     * Check if Bluetooth is enabled on the device
     */
    fun isBluetoothEnabled(context: Context): Boolean {
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Get a user-friendly error message for permission denial
     */
    fun getPermissionDenialMessage(permissionName: String): String {
        return when {
            permissionName.contains("BLUETOOTH") ->
                "Bluetooth permission is required to scan for nearby tracking devices. " +
                        "Without this permission, the app cannot function."

            permissionName.contains("LOCATION") && permissionName.contains("BACKGROUND") ->
                "Background location permission enables 24/7 protection. " +
                        "Without it, the app can only detect devices when actively open."

            permissionName.contains("LOCATION") ->
                "Location permission is required to track where devices are detected. " +
                        "This is essential for identifying if devices are following you."

            permissionName.contains("NOTIFICATION") ->
                "Notification permission is required to alert you of potential threats. " +
                        "Without it, you won't receive important security alerts."

            else -> "This permission is required for the app to function properly."
        }
    }

    /**
     * Get suggested action text for permission denial
     */
    fun getSuggestedAction(permissionName: String): String {
        return when {
            permissionName.contains("BLUETOOTH") ->
                "Please enable Bluetooth permissions in app settings to continue."

            permissionName.contains("LOCATION") && permissionName.contains("BACKGROUND") ->
                "Consider enabling background location for maximum protection. " +
                        "You can enable this later in settings."

            permissionName.contains("LOCATION") ->
                "Please enable location permissions in app settings to continue."

            permissionName.contains("NOTIFICATION") ->
                "Please enable notification permissions to receive security alerts."

            else -> "Please enable this permission in app settings."
        }
    }
}
