package com.tailbait.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.tailbait.data.database.dao.LocationDao
import com.tailbait.data.database.entities.Location
import com.tailbait.util.DistanceCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Repository interface for managing location data.
 *
 * Provides operations for location tracking, including getting current location,
 * storing location records, and querying location history.
 */
interface LocationRepository {
    /**
     * Get the current GPS location.
     *
     * @return Current location or null if unavailable
     */
    suspend fun getCurrentLocation(): Location?

    /**
     * Insert a location record into the database.
     *
     * @param location Location to insert
     * @return Location ID
     */
    suspend fun insertLocation(location: Location): Long

    /**
     * Find an existing nearby location or create a new one.
     *
     * This prevents creating duplicate location records for the same GPS position.
     * If a location exists within the specified radius, its ID is returned.
     * Otherwise, a new location is inserted.
     *
     * @param location Location to find or create
     * @param radiusMeters Maximum distance to consider as "same location" (default: 50m)
     * @return Pair of (locationId, isNew) - isNew is true if a new location was created
     */
    suspend fun findOrCreateLocation(
        location: Location,
        radiusMeters: Double = 50.0,
    ): Pair<Long, Boolean>

    /**
     * Get the current location (one-time call, not a Flow).
     * This is useful for background operations that need a single location fix.
     *
     * @return Current location or null if unavailable
     */
    suspend fun getCurrentLocationOnce(): Location?

    /**
     * Get the last known location from the database.
     *
     * @return Last location or null
     */
    suspend fun getLastLocation(): Location?

    /**
     * Get the last known location from the Android system.
     *
     * This may return a somewhat stale location but is faster and more reliable
     * than waiting for a fresh fix if one isn't immediately available.
     *
     * @return Last known system location or null
     */
    suspend fun getLastKnownLocation(): Location?

    /**
     * Get all locations as a Flow.
     *
     * @return Flow of all locations
     */
    fun getAllLocations(): Flow<List<Location>>

    /**
     * Get all locations where a specific device was detected.
     *
     * @param deviceId Device ID
     * @return Flow of locations where device was seen
     */
    fun getLocationsForDevice(deviceId: Long): Flow<List<Location>>

    /**
     * Get all locations where a specific device was detected (one-time query).
     *
     * @param deviceId Device ID
     * @return List of locations where device was seen
     */
    suspend fun getLocationsForDeviceOnce(deviceId: Long): List<Location>

    /**
     * Get all locations where a specific device OR its linked devices were detected (one-time query).
     *
     * @param deviceId Device ID
     * @return List of locations
     */
    suspend fun getLocationsForDeviceWithLinked(deviceId: Long): List<Location>

    /**
     * Delete locations older than the specified timestamp.
     *
     * @param beforeTimestamp Timestamp threshold
     * @return Number of locations deleted
     */
    suspend fun deleteOldLocations(beforeTimestamp: Long): Int

    /**
     * Delete all locations from the database.
     * WARNING: This removes all location history.
     */
    suspend fun deleteAllLocations()

    /**
     * Insert a user movement path point.
     *
     * @param locationId ID of the associated Location (place)
     * @param location Original location data (for timestamp and accuracy)
     */
    suspend fun insertUserPath(
        locationId: Long,
        location: Location,
    )

    /**
     * Get user movement path since a specific timestamp.
     *
     * @param sinceTimestamp Timestamp to start from
     * @return List of UserPath points
     */
    suspend fun getUserPathSince(sinceTimestamp: Long): List<com.tailbait.data.database.entities.UserPath>
}

/**
 * Implementation of LocationRepository.
 */
@Singleton
class LocationRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val locationDao: LocationDao,
        private val userPathDao: com.tailbait.data.database.dao.UserPathDao,
    ) : LocationRepository {
        private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        override suspend fun getCurrentLocationOnce(): Location? = getCurrentLocation()

        override suspend fun getCurrentLocation(): Location? {
            currentCoroutineContext().ensureActive()
            // Require precise location; approximate is insufficient for stalker detection
            if (!hasLocationPermission()) {
                Timber.w("Precise location permission (ACCESS_FINE_LOCATION) not granted")
                return null
            }

            return try {
                val providers = locationManager.getProviders(true)
                val provider =
                    when {
                        LocationManagerCompat.hasProvider(locationManager, "fused") &&
                            locationManager.isProviderEnabled("fused") -> "fused"
                        LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                        LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                        else -> return null
                    }
                val androidLocation = requestCurrentLocation(provider)

                if (androidLocation == null || !hasLocationPermission()) {
                    Timber.w("Fresh location unavailable; skipping stale last-known location")
                    return null
                }

                val now = System.currentTimeMillis()
                val isStale = now - androidLocation.time > MAX_STALE_LOCATION_AGE_MS
                val isInaccurate = androidLocation.accuracy > MAX_ACCURACY_METERS

                if (isStale) {
                    Timber.w("Discarding stale location (age ${(now - androidLocation.time)}ms)")
                    return null
                }
                if (isInaccurate) {
                    Timber.w("Low accuracy location detected (${androidLocation.accuracy}m), using anyway")
                }

                Location(
                    latitude = androidLocation.latitude,
                    longitude = androidLocation.longitude,
                    accuracy = androidLocation.accuracy,
                    altitude = if (androidLocation.hasAltitude()) androidLocation.altitude else null,
                    timestamp = now,
                    provider = androidLocation.provider ?: provider,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (se: SecurityException) {
                Timber.e(se, "Location permission revoked while requesting current location")
                null
            } catch (e: Exception) {
                Timber.e(e, "Error getting current location")
                null
            }
        }

        @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
        private suspend fun requestCurrentLocation(provider: String): android.location.Location? {
            val requestLock = Any()
            var listener: LocationListenerCompat? = null
            try {
                return withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        val callback =
                            object : LocationListenerCompat {
                                override fun onLocationChanged(location: android.location.Location) {
                                    if (continuation.isActive) continuation.resume(location)
                                }

                                override fun onProviderDisabled(provider: String) {
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            }
                        synchronized(requestLock) {
                            listener = callback
                            continuation.invokeOnCancellation {
                                synchronized(requestLock) {
                                    removeLocationCallback(callback)
                                }
                            }
                            if (continuation.isActive) {
                                LocationManagerCompat.requestLocationUpdates(
                                    locationManager,
                                    provider,
                                    CURRENT_LOCATION_REQUEST,
                                    callback,
                                    Looper.getMainLooper(),
                                )
                            }
                        }
                    }
                }
            } finally {
                synchronized(requestLock) {
                    listener?.let(::removeLocationCallback)
                }
            }
        }

        private fun removeLocationCallback(callback: LocationListenerCompat) {
            try {
                LocationManagerCompat.removeUpdates(locationManager, callback)
            } catch (se: SecurityException) {
                Timber.w(se, "Location permission revoked while removing current location request")
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove current location request")
            }
        }

        override suspend fun insertLocation(location: Location): Long {
            return locationDao.insert(location)
        }

        override suspend fun findOrCreateLocation(
            location: Location,
            radiusMeters: Double,
        ): Pair<Long, Boolean> {
            // Optimization: Use SQL bounding box query instead of loading all locations
            // 1 degree of latitude is roughly 111km. 100m is roughly 0.001 degrees.
            // We use a safe margin of 0.002 degrees for the bounding box.
            val degreesDelta = (radiusMeters / 111000.0) * 1.5 // 1.5x safety margin

            val latMin = location.latitude - degreesDelta
            val latMax = location.latitude + degreesDelta

            // Longitude delta depends on latitude, but for small distances (50m)
            // using the same delta is safe enough as a crude bounding box
            val lonMin = location.longitude - degreesDelta
            val lonMax = location.longitude + degreesDelta

            // Search all time to prevent duplicate locations when returning to a place after >24h
            val nearbyCandidates =
                locationDao.getNearbyCandidatesAllTime(
                    latMin,
                    latMax,
                    lonMin,
                    lonMax,
                )

            // Refine with exact distance calculation
            for (existing in nearbyCandidates) {
                val distance =
                    DistanceCalculator.calculateDistance(
                        location.latitude,
                        location.longitude,
                        existing.latitude,
                        existing.longitude,
                    )
                if (distance <= radiusMeters) {
                    // Found an existing nearby location - update its timestamp
                    locationDao.updateTimestamp(existing.id, location.timestamp)
                    Timber.d("Reusing existing location ${existing.id} (${distance.toInt()}m away)")
                    return Pair(existing.id, false)
                }
            }

            // No nearby location found - create a new one
            val newId = locationDao.insert(location)
            Timber.d("Created new location $newId")
            return Pair(newId, true)
        }

        override suspend fun getLastLocation(): Location? {
            return locationDao.getLastLocation()
        }

        override suspend fun getLastKnownLocation(): Location? {
            currentCoroutineContext().ensureActive()
            if (!hasLocationPermission()) {
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
            } catch (e: CancellationException) {
                throw e
            } catch (se: SecurityException) {
                Timber.e(se, "Location permission revoked while requesting last known location")
                null
            } catch (e: Exception) {
                Timber.e(e, "Error getting last known location")
                null
            }
        }

        override fun getAllLocations(): Flow<List<Location>> {
            return locationDao.getAllLocations()
        }

        override fun getLocationsForDevice(deviceId: Long): Flow<List<Location>> {
            return locationDao.getLocationsForDevice(deviceId)
        }

        override suspend fun getLocationsForDeviceOnce(deviceId: Long): List<Location> {
            return locationDao.getLocationsForDeviceOnce(deviceId)
        }

        override suspend fun getLocationsForDeviceWithLinked(deviceId: Long): List<Location> {
            return locationDao.getLocationsForDeviceWithLinked(deviceId)
        }

        override suspend fun deleteOldLocations(beforeTimestamp: Long): Int {
            return locationDao.deleteOldLocations(beforeTimestamp)
        }

        override suspend fun deleteAllLocations() {
            locationDao.deleteAll()
        }

        /**
         * Check whether we have precise location permission (fine).
         */
        private fun hasLocationPermission(): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }

        override suspend fun insertUserPath(
            locationId: Long,
            location: Location,
        ) {
            val userPath =
                com.tailbait.data.database.entities.UserPath(
                    locationId = locationId,
                    timestamp = location.timestamp,
                    accuracy = location.accuracy,
                )
            userPathDao.insert(userPath)
        }

        override suspend fun getUserPathSince(sinceTimestamp: Long): List<com.tailbait.data.database.entities.UserPath> {
            return userPathDao.getUserPathSince(sinceTimestamp)
        }

        companion object {
            private const val CURRENT_LOCATION_TIMEOUT_MS = 30_000L
            private const val MAX_STALE_LOCATION_AGE_MS = 2 * 60 * 1000L // 2 minutes
            private const val MAX_ACCURACY_METERS = 200f
            private val CURRENT_LOCATION_REQUEST =
                LocationRequestCompat.Builder(0L)
                    .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                    .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MS)
                    .setMaxUpdates(1)
                    .build()
        }
    }
