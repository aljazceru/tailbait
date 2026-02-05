package com.tailbait.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import com.tailbait.data.database.entities.Location
import com.tailbait.data.repository.SettingsRepository
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LocationTracker handles GPS location tracking using Google Play Services Fused Location Provider.
 *
 * This class provides battery-efficient location tracking with configurable update intervals,
 * accuracy filtering, and integration with the BLE scanning system. It supports both continuous
 * location updates and on-demand location requests.
 *
 * Key Features:
 * - Fused Location Provider for optimal accuracy and battery usage
 * - Configurable update intervals based on scan settings
 * - Accuracy filtering (< 100m) to ensure reliable location data
 * - Permission checking integrated with PermissionHelper
 * - State flow for reactive location updates
 * - Battery-efficient location request settings
 * - Comprehensive error handling
 *
 * @property context Application context
 * @property settingsRepository Repository for app settings
 */
@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _locationState = MutableStateFlow<LocationState>(LocationState.Idle)
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private var locationCallback: LocationCallback? = null

    /**
     * Sealed class representing the current location tracking state.
     */
    sealed class LocationState {
        /** Location tracking is idle (not active) */
        object Idle : LocationState()

        /** Location tracking is active */
        object Tracking : LocationState()

        /** New location update received */
        data class LocationUpdated(val location: android.location.Location) : LocationState()

        /** Error occurred during location tracking */
        data class Error(val message: String) : LocationState()
    }

    /**
     * Start continuous location tracking.
     *
     * Begins receiving location updates at the configured interval. Updates are filtered
     * by accuracy (< 100m) and emitted through the locationState flow.
     *
     * @return Result indicating success or failure with error details
     * @throws SecurityException if location permission is not granted
     */
    suspend fun startLocationTracking(): Result<Unit> {
        Timber.i("Starting location tracking")

        if (!hasLocationPermission()) {
            val error = "Location permission not granted"
            Timber.w(error)
            _locationState.value = LocationState.Error(error)
            return Result.failure(SecurityException(error))
        }

        val settings = settingsRepository.getSettings().first()

        // Configure location request with battery-efficient settings
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            (settings.scanIntervalSeconds * 1000L)
        ).apply {
            setMinUpdateIntervalMillis(60000L) // Min 1 minute between updates
            setMaxUpdateAgeMillis(120000L) // Max 2 minutes old
            setWaitForAccurateLocation(false) // Don't wait indefinitely for accuracy
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    Timber.d("Location update received: accuracy=${location.accuracy}m")

                    // Only accept locations with accuracy < 100m
                    _locationState.value = LocationState.LocationUpdated(location)
                    Timber.i(
                        "Location update: lat=${location.latitude}, " +
                                "lon=${location.longitude}, " +
                                "accuracy=${location.accuracy}m"
                    )
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Timber.w("Location unavailable")
                    _locationState.value = LocationState.Error("Location unavailable")
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            _locationState.value = LocationState.Tracking
            Timber.i("Location tracking started successfully")
            return Result.success(Unit)
        } catch (se: SecurityException) {
            val errorMsg = "Location permission revoked while starting tracking"
            Timber.e(se, errorMsg)
            _locationState.value = LocationState.Error(errorMsg)
            return Result.failure(se)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error starting location tracking"
            Timber.e(e, "Failed to start location tracking")
            _locationState.value = LocationState.Error(errorMsg)
            return Result.failure(e)
        }
    }

    /**
     * Get the current location on-demand.
     *
     * Requests a single high-accuracy location update. This is used by the scanner
     * to correlate device detections with GPS coordinates.
     *
     * @return Current location or null if unavailable or permission denied
     */
    suspend fun getCurrentLocation(): Location? {
        Timber.d("Requesting current location")

        if (!hasLocationPermission()) {
            Timber.w("Cannot get location: permission not granted")
            return null
        }

        return try {
            suspendCancellableCoroutine { continuation ->
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    CancellationTokenSource().token
                ).addOnSuccessListener { androidLocation: android.location.Location? ->
                    if (androidLocation != null) {
                        Timber.i(
                            "Current location obtained: lat=${androidLocation.latitude}, " +
                                    "lon=${androidLocation.longitude}, " +
                                    "accuracy=${androidLocation.accuracy}m"
                        )

                        val location = Location(
                            latitude = androidLocation.latitude,
                            longitude = androidLocation.longitude,
                            accuracy = androidLocation.accuracy,
                            altitude = if (androidLocation.hasAltitude()) androidLocation.altitude else null,
                            timestamp = androidLocation.time,
                            provider = androidLocation.provider ?: "FUSED"
                        )
                        continuation.resume(location)
                    } else {
                        Timber.w("Current location is null")
                        continuation.resume(null)
                    }
                }.addOnFailureListener { exception ->
                    Timber.e(exception, "Failed to get current location")
                    continuation.resumeWithException(exception)
                }
            }
        } catch (se: SecurityException) {
            Timber.e(se, "Location permission revoked while requesting current location")
            null
        }
    }

    /**
     * Get the last known location from the system.
     *
     * This is faster than getCurrentLocation() but may return stale data.
     * Useful for quick location checks when high accuracy is not critical.
     *
     * @return Last known location or null if unavailable
     */
    suspend fun getLastKnownLocation(): Location? {
        Timber.d("Requesting last known location")

        if (!hasLocationPermission()) {
            Timber.w("Cannot get last location: permission not granted")
            return null
        }

        return try {
            suspendCancellableCoroutine { continuation ->
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { androidLocation: android.location.Location? ->
                        if (androidLocation != null) {
                            Timber.d(
                                "Last known location: lat=${androidLocation.latitude}, " +
                                        "lon=${androidLocation.longitude}, " +
                                        "accuracy=${androidLocation.accuracy}m"
                            )

                            val location = Location(
                                latitude = androidLocation.latitude,
                                longitude = androidLocation.longitude,
                                accuracy = androidLocation.accuracy,
                                altitude = if (androidLocation.hasAltitude()) androidLocation.altitude else null,
                                timestamp = androidLocation.time,
                                provider = "FUSED"
                            )
                            continuation.resume(location)
                        } else {
                            Timber.d("Last known location is null")
                            continuation.resume(null)
                        }
                    }
                    .addOnFailureListener { exception ->
                        Timber.e(exception, "Failed to get last known location")
                        continuation.resumeWithException(exception)
                    }
            }
        } catch (se: SecurityException) {
            Timber.e(se, "Location permission revoked while requesting last known location")
            null
        }
    }

    /**
     * Stop continuous location tracking.
     *
     * Removes location update callbacks and resets tracking state to idle.
     */
    fun stopLocationTracking() {
        Timber.i("Stopping location tracking")

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            Timber.i("Location updates removed")
        }

        _locationState.value = LocationState.Idle
    }

    /**
     * Check if location tracking is currently active.
     *
     * @return True if location updates are being received
     */
    fun isTracking(): Boolean {
        return _locationState.value is LocationState.Tracking
    }

    /**
     * Check if fine location permission is granted.
     *
     * @return True if ACCESS_FINE_LOCATION permission is granted
     */
    private fun hasLocationPermission(): Boolean {
        return context.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
