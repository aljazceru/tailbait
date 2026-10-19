package com.tailbait.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.tailbait.data.database.entities.Location
import com.tailbait.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * LocationTracker handles GPS location tracking using Android's native location providers.
 *
 * This class provides battery-efficient location tracking with configurable update intervals,
 * accuracy filtering, and integration with the BLE scanning system. It supports both continuous
 * location updates and on-demand location requests.
 *
 * Key Features:
 * - Native fused, network, or GPS provider selection for accuracy and battery usage
 * - Configurable update intervals based on scan settings
 * - Accuracy filtering (up to 200m) to ensure reliable location data
 * - Permission checking integrated with PermissionHelper
 * - State flow for reactive location updates
 * - Battery-efficient location request settings
 * - Comprehensive error handling
 *
 * @property context Application context
 * @property settingsRepository Repository for app settings
 */
@Singleton
class LocationTracker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
    ) {
        private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        private val _locationState = MutableStateFlow<LocationState>(LocationState.Idle)
        val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

        private val registrationLock = Any()
        private var registrationGeneration = 0L
        private var locationCallback: LocationListenerCompat? = null
        private var updateIntervalSeconds: Int? = null
        private var locationProvider: String? = null
        private var locationQuality = 0
        private val currentLocationRequests =
            mutableMapOf<LocationListenerCompat, CancellableContinuation<android.location.Location?>>()

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
         * Start or replace continuous location tracking at the configured interval.
         * Updates with accuracy up to 200m are emitted through locationState.
         *
         * @param scanIntervalSeconds Current service interval, or null to read persisted settings.
         * @return Whether native location updates were registered.
         */
        suspend fun startLocationTracking(scanIntervalSeconds: Int? = null): Result<Unit> {
            currentCoroutineContext().ensureActive()
            val generation = synchronized(registrationLock) { registrationGeneration }
            val interval = scanIntervalSeconds ?: settingsRepository.getSettingsOnce().scanIntervalSeconds
            return synchronized(registrationLock) {
                // A stop during the settings read must not register a late callback.
                if (generation != registrationGeneration) {
                    return@synchronized Result.failure(CancellationException("Location tracking was stopped"))
                }
                if (!hasLocationPermission()) {
                    stopLocationTracking()
                    val error = "Location permission not granted"
                    _locationState.value = LocationState.Error(error)
                    return@synchronized Result.failure(SecurityException(error))
                }
                try {
                    val providers = locationManager.getProviders(true)
                    val provider =
                        when {
                            LocationManagerCompat.hasProvider(locationManager, "fused") &&
                                locationManager.isProviderEnabled("fused") -> "fused"
                            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                            else -> throw IllegalStateException("Location unavailable: no enabled provider")
                        }
                    // A fused provider without network must be allowed to activate GPS.
                    val quality =
                        if (LocationManager.NETWORK_PROVIDER in providers) {
                            LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
                        } else {
                            LocationRequestCompat.QUALITY_HIGH_ACCURACY
                        }
                    if (locationCallback != null && updateIntervalSeconds == interval &&
                        locationProvider == provider && locationQuality == quality
                    ) {
                        return@synchronized Result.success(Unit)
                    }
                    stopLocationTracking()
                    val request =
                        LocationRequestCompat.Builder(interval * 1000L)
                            .setMinUpdateIntervalMillis(60_000L)
                            .setQuality(quality)
                            .build()
                    val callback =
                        object : LocationListenerCompat {
                            override fun onLocationChanged(location: android.location.Location) {
                                synchronized(registrationLock) {
                                    if (locationCallback !== this) return
                                    if (!hasLocationPermission()) {
                                        stopLocationTracking()
                                        _locationState.value = LocationState.Error("Location permission revoked")
                                    } else if (
                                        location.accuracy.isFinite() && location.accuracy in 0f..200f &&
                                        System.currentTimeMillis() - location.time <= MAX_STALE_LOCATION_AGE_MS
                                    ) {
                                        _locationState.value = LocationState.LocationUpdated(location)
                                    }
                                }
                            }

                            override fun onProviderDisabled(provider: String) {
                                synchronized(registrationLock) {
                                    if (locationCallback === this) {
                                        _locationState.value = LocationState.Error("Location unavailable")
                                    }
                                }
                            }
                        }
                    locationCallback = callback
                    updateIntervalSeconds = interval
                    locationProvider = provider
                    locationQuality = quality
                    LocationManagerCompat.requestLocationUpdates(
                        locationManager,
                        provider,
                        request,
                        callback,
                        Looper.getMainLooper(),
                    )
                    if (_locationState.value !is LocationState.LocationUpdated) {
                        _locationState.value = LocationState.Tracking
                    }
                    Result.success(Unit)
                } catch (e: CancellationException) {
                    stopLocationTracking()
                    throw e
                } catch (se: SecurityException) {
                    stopLocationTracking()
                    _locationState.value = LocationState.Error(se.message ?: "Location permission revoked")
                    Result.failure(se)
                } catch (e: Exception) {
                    stopLocationTracking()
                    _locationState.value = LocationState.Error(e.message ?: "Location registration failed")
                    Result.failure(e)
                }
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
            currentCoroutineContext().ensureActive()
            val generation = synchronized(registrationLock) { registrationGeneration }
            Timber.d("Requesting current location")

            if (!hasLocationPermission()) {
                Timber.w("Cannot get location: permission not granted")
                return null
            }

            return try {
                val androidLocation =
                    withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS) {
                        suspendCancellableCoroutine<android.location.Location?> { continuation ->
                            synchronized(registrationLock) {
                                if (generation != registrationGeneration) {
                                    continuation.cancel(CancellationException("Location tracking was stopped"))
                                    return@synchronized
                                }
                                val providers = locationManager.getProviders(true)
                                val provider =
                                    when {
                                        LocationManagerCompat.hasProvider(locationManager, "fused") &&
                                            locationManager.isProviderEnabled("fused") -> "fused"
                                        LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                                        LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                                        else -> {
                                            continuation.resume(null)
                                            return@synchronized
                                        }
                                    }
                                val callback =
                                    object : LocationListenerCompat {
                                        override fun onLocationChanged(location: android.location.Location) {
                                            finishCurrentLocation(this, location)
                                        }

                                        override fun onProviderDisabled(provider: String) {
                                            finishCurrentLocation(this, null)
                                        }
                                    }
                                currentLocationRequests[callback] = continuation
                                continuation.invokeOnCancellation {
                                    synchronized(registrationLock) {
                                        currentLocationRequests.remove(callback)
                                        removeLocationCallback(callback)
                                    }
                                }
                                if (continuation.isActive) {
                                    try {
                                        LocationManagerCompat.requestLocationUpdates(
                                            locationManager,
                                            provider,
                                            CURRENT_LOCATION_REQUEST,
                                            callback,
                                            Looper.getMainLooper(),
                                        )
                                    } catch (e: Exception) {
                                        currentLocationRequests.remove(callback)
                                        removeLocationCallback(callback)
                                        throw e
                                    }
                                }
                            }
                        }
                    } ?: return null
                synchronized(registrationLock) {
                    if (generation != registrationGeneration) {
                        throw CancellationException("Location tracking was stopped")
                    }
                }
                if (!hasLocationPermission() ||
                    System.currentTimeMillis() - androidLocation.time > MAX_STALE_LOCATION_AGE_MS
                ) {
                    return null
                }
                Location(
                    latitude = androidLocation.latitude,
                    longitude = androidLocation.longitude,
                    accuracy = androidLocation.accuracy,
                    altitude = if (androidLocation.hasAltitude()) androidLocation.altitude else null,
                    timestamp = androidLocation.time,
                    provider = androidLocation.provider ?: "unknown",
                )
            } catch (se: SecurityException) {
                Timber.e(se, "Location permission revoked while requesting current location")
                null
            }
        }

        private fun finishCurrentLocation(
            callback: LocationListenerCompat,
            location: android.location.Location?,
        ) {
            synchronized(registrationLock) {
                val continuation = currentLocationRequests.remove(callback) ?: return
                removeLocationCallback(callback)
                if (continuation.isActive) continuation.resume(location)
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
            currentCoroutineContext().ensureActive()
            Timber.d("Requesting last known location")

            if (!hasLocationPermission()) {
                Timber.w("Cannot get last location: permission not granted")
                return null
            }

            return try {
                var newest: android.location.Location? = null
                for (provider in locationManager.getProviders(true)) {
                    val candidate = locationManager.getLastKnownLocation(provider) ?: continue
                    if (newest == null || candidate.elapsedRealtimeNanos > newest.elapsedRealtimeNanos) {
                        newest = candidate
                    }
                }
                val androidLocation = newest ?: return null
                Location(
                    latitude = androidLocation.latitude,
                    longitude = androidLocation.longitude,
                    accuracy = androidLocation.accuracy,
                    altitude = if (androidLocation.hasAltitude()) androidLocation.altitude else null,
                    timestamp = androidLocation.time,
                    provider = androidLocation.provider ?: "unknown",
                )
            } catch (se: SecurityException) {
                Timber.e(se, "Location permission revoked while requesting last known location")
                null
            }
        }

        /**
         * Stop continuous and pending one-shot location tracking.
         *
         * Removes native listeners, cancels pending requests, and resets tracking state to idle.
         */
        fun stopLocationTracking() {
            synchronized(registrationLock) {
                registrationGeneration++
                val callback = locationCallback
                locationCallback = null
                updateIntervalSeconds = null
                locationProvider = null
                locationQuality = 0
                _locationState.value = LocationState.Idle
                callback?.let(::removeLocationCallback)
                val pending = currentLocationRequests.iterator()
                while (pending.hasNext()) {
                    val (listener, continuation) = pending.next()
                    pending.remove()
                    removeLocationCallback(listener)
                    continuation.cancel(CancellationException("Location tracking was stopped"))
                }
            }
        }

        private fun removeLocationCallback(callback: LocationListenerCompat) {
            try {
                LocationManagerCompat.removeUpdates(locationManager, callback)
            } catch (se: SecurityException) {
                Timber.w(se, "Location permission revoked while removing location updates")
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove location updates")
            }
        }

        /**
         * Check if location tracking is currently active.
         *
         * @return True if location updates are being received
         */
        fun isTracking(): Boolean {
            return synchronized(registrationLock) { locationCallback != null }
        }

        /**
         * Check if fine location permission is granted.
         *
         * @return True if ACCESS_FINE_LOCATION permission is granted
         */
        private fun hasLocationPermission(): Boolean {
            return context.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }

        companion object {
            private const val CURRENT_LOCATION_TIMEOUT_MS = 30_000L
            private const val MAX_STALE_LOCATION_AGE_MS = 120_000L
            private val CURRENT_LOCATION_REQUEST =
                LocationRequestCompat.Builder(0L)
                    .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                    .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MS)
                    .setMaxUpdates(1)
                    .build()
        }
    }
