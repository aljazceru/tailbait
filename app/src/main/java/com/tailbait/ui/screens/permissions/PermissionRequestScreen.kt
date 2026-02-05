package com.tailbait.ui.screens.permissions

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import com.tailbait.ui.theme.TailBaitDimensions
import com.tailbait.ui.theme.TailBaitShapeTokens
import com.tailbait.util.PermissionHelper
import com.google.accompanist.permissions.*

/**
 * Main permission request screen that guides users through granting required permissions.
 * Uses Accompanist Permissions library for Compose-friendly permission handling.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRequestScreen(
    onPermissionsGranted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionRequestViewModel = hiltViewModel()
) {
    val permissionHelper = viewModel.permissionHelper

    // Define permission states using Accompanist
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rememberMultiplePermissionsState(
            permissions = listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    } else {
        null
    }

    val locationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    val backgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        rememberPermissionState(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        null
    }

    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }

    // Track permission grant states
    LaunchedEffect(
        bluetoothPermissions?.allPermissionsGranted,
        locationPermissions.allPermissionsGranted,
        backgroundLocationPermission?.status?.isGranted,
        notificationPermission?.status?.isGranted
    ) {
        permissionHelper.updatePermissionStates()

        // Check if all essential permissions are granted
        if (permissionHelper.areEssentialPermissionsGranted()) {
            onPermissionsGranted()
        }
    }

    // Show rationale dialogs
    var showBluetoothRationaleDialog by remember { mutableStateOf(false) }
    var showLocationRationaleDialog by remember { mutableStateOf(false) }
    var showBackgroundLocationRationaleDialog by remember { mutableStateOf(false) }
    var showNotificationRationaleDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TailBaitDimensions.SpacingLG),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXXXL))

        // Header
        Text(
            text = "Required Permissions",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingSM))

        Text(
            text = "To protect you from potential tracking devices, we need the following permissions:",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXXXL))

        // Bluetooth Permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bluetoothPermissions != null) {
            PermissionCard(
                title = "Bluetooth Access",
                description = permissionHelper.getShortPermissionRationale(PermissionHelper.PermissionGroup.BLUETOOTH),
                isGranted = bluetoothPermissions.allPermissionsGranted,
                isRequired = true,
                onRequestPermission = {
                    if (bluetoothPermissions.shouldShowRationale) {
                        showBluetoothRationaleDialog = true
                    } else {
                        bluetoothPermissions.launchMultiplePermissionRequest()
                    }
                },
                onShowRationale = { showBluetoothRationaleDialog = true }
            )

            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingLG))
        }

        // Location Permissions
    PermissionCard(
        title = "Location Access",
        description = permissionHelper.getShortPermissionRationale(
            PermissionHelper.PermissionGroup.LOCATION
        ),
        isGranted = locationPermissions.allPermissionsGranted,
        isRequired = true,
        onRequestPermission = {
            if (locationPermissions.shouldShowRationale) {
                showLocationRationaleDialog = true
                } else {
                    locationPermissions.launchMultiplePermissionRequest()
                }
            },
            onShowRationale = { showLocationRationaleDialog = true }
        )

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingLG))

        // Background Location Permission (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && backgroundLocationPermission != null) {
        PermissionCard(
            title = "Background Location",
            description = permissionHelper.getShortPermissionRationale(
                PermissionHelper.PermissionGroup.BACKGROUND_LOCATION
            ),
            isGranted = backgroundLocationPermission.status.isGranted,
            isRequired = false,
            onRequestPermission = {
                if (backgroundLocationPermission.status.shouldShowRationale) {
                    showBackgroundLocationRationaleDialog = true
                    } else {
                        backgroundLocationPermission.launchPermissionRequest()
                    }
                },
                onShowRationale = { showBackgroundLocationRationaleDialog = true }
            )

            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingLG))
        }

        // Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notificationPermission != null) {
        PermissionCard(
            title = "Notifications",
            description = permissionHelper.getShortPermissionRationale(
                PermissionHelper.PermissionGroup.NOTIFICATIONS
            ),
            isGranted = notificationPermission.status.isGranted,
            isRequired = true,
            onRequestPermission = {
                if (notificationPermission.status.shouldShowRationale) {
                    showNotificationRationaleDialog = true
                    } else {
                        notificationPermission.launchPermissionRequest()
                    }
                },
                onShowRationale = { showNotificationRationaleDialog = true }
            )

            Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingLG))
        }

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXXXL))

        // Continue button (enabled when essential permissions are granted)
        Button(
            onClick = onPermissionsGranted,
            enabled = permissionHelper.areEssentialPermissionsGranted(),
            modifier = Modifier
                .fillMaxWidth()
                .height(TailBaitDimensions.ButtonHeight),
            shape = TailBaitShapeTokens.ButtonShape
        ) {
            Text(
                text = if (permissionHelper.areEssentialPermissionsGranted()) {
                    "Continue"
                } else {
                    "Grant Required Permissions First"
                },
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingLG))

        // Info text
        if (!permissionHelper.areEssentialPermissionsGranted()) {
            Text(
                text = "Please grant all required permissions to continue. " +
                        "Tap on each permission card to learn more.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!permissionHelper.hasBackgroundLocationPermission()) {
            Text(
                text = "Tip: Grant background location permission for 24/7 protection.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(TailBaitDimensions.SpacingXXXL))
    }

    // Rationale Dialogs
    if (showBluetoothRationaleDialog) {
        PermissionRationaleDialog(
            title = "Bluetooth Permission",
            message = permissionHelper.getPermissionRationale(PermissionHelper.PermissionGroup.BLUETOOTH),
            onDismiss = { showBluetoothRationaleDialog = false },
            onConfirm = {
                showBluetoothRationaleDialog = false
                bluetoothPermissions?.launchMultiplePermissionRequest()
            }
        )
    }

    if (showLocationRationaleDialog) {
        PermissionRationaleDialog(
            title = "Location Permission",
            message = permissionHelper.getPermissionRationale(PermissionHelper.PermissionGroup.LOCATION),
            onDismiss = { showLocationRationaleDialog = false },
            onConfirm = {
                showLocationRationaleDialog = false
                locationPermissions.launchMultiplePermissionRequest()
            }
        )
    }

    if (showBackgroundLocationRationaleDialog) {
        PermissionRationaleDialog(
            title = "Background Location Permission",
            message = permissionHelper.getPermissionRationale(PermissionHelper.PermissionGroup.BACKGROUND_LOCATION),
            onDismiss = { showBackgroundLocationRationaleDialog = false },
            onConfirm = {
                showBackgroundLocationRationaleDialog = false
                backgroundLocationPermission?.launchPermissionRequest()
            }
        )
    }

    if (showNotificationRationaleDialog) {
        PermissionRationaleDialog(
            title = "Notification Permission",
            message = permissionHelper.getPermissionRationale(PermissionHelper.PermissionGroup.NOTIFICATIONS),
            onDismiss = { showNotificationRationaleDialog = false },
            onConfirm = {
                showNotificationRationaleDialog = false
                notificationPermission?.launchPermissionRequest()
            }
        )
    }
}
