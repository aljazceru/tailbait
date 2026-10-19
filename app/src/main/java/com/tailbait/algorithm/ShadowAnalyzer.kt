package com.tailbait.algorithm

import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analyzes device shadows (coarse BLE profiles) to detect suspicious devices
 * without requiring explicit MAC-to-MAC linking.
 *
 * ## How It Works
 * 1. Query shadow keys appearing at N+ user locations
 * 2. For each key, count how many distinct devices match per location
 * 3. Compute persistence score: a shadow with exactly 1 match at every
 *    location is suspicious (same physical device rotating MACs)
 * 4. Use [MacRotationDetector] for corroborating evidence
 * 5. Combine scores and return results above threshold
 *
 * ## Persistence Score Formula
 * ```
 * persistenceScore = (1.0 / (1.0 + variance)) * specificityMultiplier * locationCoverage
 * ```
 * Where:
 * - variance: statistical variance of per-location device counts
 * - specificityMultiplier: how specific the shadow key is (componentCount / MAX)
 * - locationCoverage: fraction of user's locations where shadow appeared
 */
@Singleton
class ShadowAnalyzer
    @Inject
    constructor(
        private val deviceRepository: DeviceRepository,
        private val locationRepository: LocationRepository,
        private val macRotationDetector: MacRotationDetector,
    ) {
        companion object {
            private const val TAG = "ShadowAnalyzer"

            /** Minimum combined score to report a shadow as suspicious. */
            const val MIN_COMBINED_SCORE = 0.3

            /** Weight of persistence score in combined score. */
            private const val PERSISTENCE_WEIGHT = 0.7

            /** Weight of rotation detection score in combined score. */
            private const val ROTATION_WEIGHT = 0.3
        }

        /**
         * Result of shadow-based detection for a single shadow key.
         *
         * @property representativeDevice Most recently seen device matching this shadow.
         * @property shadowKey The shadow key that triggered detection.
         * @property persistenceScore How consistently exactly 1 device matches per location (0.0-1.0).
         * @property rotationScore MAC rotation rhythm evidence (0.0-1.0).
         * @property locationCount Number of user locations where this shadow appeared.
         * @property combinedScore Weighted combination of persistence + rotation scores.
         */
        data class ShadowDetectionResult(
            val representativeDevice: ScannedDevice,
            val shadowKey: String,
            val persistenceScore: Double,
            val rotationScore: Double,
            val locationCount: Int,
            val combinedScore: Double,
            val records: List<DeviceLocationRecord>,
            val locations: List<Location>,
        )

        /**
         * Find suspicious shadows across the user's location history.
         *
         * @param minLocationCount Minimum locations a shadow must appear at.
         * @param whitelistedIds Device IDs to exclude from results.
         * @param sinceTimestamp Earliest eligible observation time.
         * @return List of shadow detection results above [MIN_COMBINED_SCORE].
         */
        suspend fun findSuspiciousShadows(
            minLocationCount: Int,
            whitelistedIds: Set<Long>,
            sinceTimestamp: Long,
        ): List<ShadowDetectionResult> {
            val shadowKeys = deviceRepository.getSuspiciousShadowKeys(minLocationCount, sinceTimestamp)
            if (shadowKeys.isEmpty()) {
                Timber.tag(TAG).d("No shadow keys at $minLocationCount+ locations")
                return emptyList()
            }

            val locationsById = locationRepository.getAllLocations().first().associateBy { it.id }
            val pathLocationIds =
                locationRepository.getUserPathSince(sinceTimestamp)
                    .filter { it.timestamp >= sinceTimestamp }
                    .map { it.locationId }
                    .toSet()
            val currentLocationIds =
                locationsById.values.filter { it.timestamp >= sinceTimestamp || it.id in pathLocationIds }
                    .map { it.id }
                    .toSet()

            Timber.tag(TAG).d("Analyzing ${shadowKeys.size} shadow keys across ${currentLocationIds.size} user locations")

            val results = mutableListOf<ShadowDetectionResult>()

            for (shadowKey in shadowKeys) {
                val result =
                    analyzeShadow(
                        shadowKey,
                        minLocationCount,
                        locationsById,
                        currentLocationIds,
                        whitelistedIds,
                        sinceTimestamp,
                    )
                if (result != null && result.combinedScore >= MIN_COMBINED_SCORE) {
                    results.add(result)
                }
            }

            return results.sortedByDescending { it.combinedScore }
        }

        private suspend fun analyzeShadow(
            shadowKey: String,
            minLocationCount: Int,
            locationsById: Map<Long, Location>,
            currentLocationIds: Set<Long>,
            whitelistedIds: Set<Long>,
            sinceTimestamp: Long,
        ): ShadowDetectionResult? {
            val devices =
                deviceRepository.getDevicesByShadowKey(shadowKey, sinceTimestamp)
                    .filter { it.id !in whitelistedIds }
            val eligibleDeviceIds = devices.map { it.id }.toSet()
            val records =
                deviceRepository.getDeviceLocationRecordsForShadow(shadowKey, sinceTimestamp)
                    .filter {
                        it.timestamp >= sinceTimestamp &&
                            it.deviceId in eligibleDeviceIds &&
                            it.locationId in locationsById
                    }
            val recordsByLocation = records.groupBy { it.locationId }
            if (recordsByLocation.size < minLocationCount) return null
            val locations = recordsByLocation.keys.map { locationsById.getValue(it) }

            // Derive every score from the same eligible observations, not lifetime MAC summaries.
            val counts =
                recordsByLocation.values.map { atLocation ->
                    atLocation.mapTo(mutableSetOf()) { it.deviceId }.size.toDouble()
                }
            val mean = counts.average()
            val variance = counts.sumOf { (it - mean) * (it - mean) } / counts.size
            val specificityMultiplier = ShadowKeyGenerator.specificityScore(shadowKey).toDouble()
            val totalUserLocations = currentLocationIds.size + recordsByLocation.keys.count { it !in currentLocationIds }
            val locationCoverage = locations.size.toDouble() / totalUserLocations
            val persistenceScore = (1.0 / (1.0 + variance)) * specificityMultiplier * locationCoverage

            val recordsByDevice = records.groupBy { it.deviceId }
            val scopedDevices =
                devices.mapNotNull { device ->
                    val observations = recordsByDevice[device.id] ?: return@mapNotNull null
                    val hasCurrentStatus =
                        device.findMyStatus != null && (device.findMyStatusTimestamp ?: Long.MIN_VALUE) >= sinceTimestamp
                    device.copy(
                        firstSeen = observations.minOf { it.timestamp },
                        lastSeen = observations.maxOf { it.timestamp },
                        highestRssi = observations.maxOf { it.rssi },
                        findMyStatus = device.findMyStatus.takeIf { hasCurrentStatus },
                        findMyStatusTimestamp = device.findMyStatusTimestamp.takeIf { hasCurrentStatus },
                        findMySeparated = hasCurrentStatus && device.findMySeparated,
                    )
                }
            val rotationResult = macRotationDetector.detectRotation(scopedDevices)
            val combinedScore =
                (persistenceScore * PERSISTENCE_WEIGHT) +
                    (rotationResult.score * ROTATION_WEIGHT)
            val representative =
                scopedDevices.maxWithOrNull(compareBy<ScannedDevice> { it.lastSeen }.thenBy { it.highestRssi })
                    ?: return null

            Timber.tag(TAG).d(
                "Shadow $shadowKey: persistence=%.2f, rotation=%.2f, combined=%.2f, " +
                    "locations=${locations.size}, devices=${scopedDevices.size}",
                persistenceScore,
                rotationResult.score,
                combinedScore,
            )

            return ShadowDetectionResult(
                representativeDevice = representative,
                shadowKey = shadowKey,
                persistenceScore = persistenceScore,
                rotationScore = rotationResult.score,
                locationCount = locations.size,
                combinedScore = combinedScore,
                records = records,
                locations = locations,
            )
        }
    }
