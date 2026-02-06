package com.tailbait.algorithm

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
class ShadowAnalyzer @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val locationRepository: LocationRepository,
    private val macRotationDetector: MacRotationDetector
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
        val combinedScore: Double
    )

    /**
     * Find suspicious shadows across the user's location history.
     *
     * @param minLocationCount Minimum locations a shadow must appear at.
     * @param whitelistedIds Device IDs to exclude from results.
     * @return List of shadow detection results above [MIN_COMBINED_SCORE].
     */
    suspend fun findSuspiciousShadows(
        minLocationCount: Int,
        whitelistedIds: Set<Long>
    ): List<ShadowDetectionResult> {
        val shadowKeys = deviceRepository.getSuspiciousShadowKeys(minLocationCount)
        if (shadowKeys.isEmpty()) {
            Timber.tag(TAG).d("No shadow keys at $minLocationCount+ locations")
            return emptyList()
        }

        val totalUserLocations = locationRepository.getAllLocations().first().size
        if (totalUserLocations == 0) return emptyList()

        Timber.tag(TAG).d("Analyzing ${shadowKeys.size} shadow keys across $totalUserLocations user locations")

        val results = mutableListOf<ShadowDetectionResult>()

        for (shadowKey in shadowKeys) {
            val result = analyzeShadow(shadowKey, totalUserLocations, whitelistedIds)
            if (result != null && result.combinedScore >= MIN_COMBINED_SCORE) {
                results.add(result)
            }
        }

        return results.sortedByDescending { it.combinedScore }
    }

    private suspend fun analyzeShadow(
        shadowKey: String,
        totalUserLocations: Int,
        whitelistedIds: Set<Long>
    ): ShadowDetectionResult? {
        // Get per-location device counts
        val locationCounts = deviceRepository.getShadowLocationDeviceCounts(shadowKey)
        if (locationCounts.isEmpty()) return null

        // Compute persistence score
        val counts = locationCounts.map { it.deviceCount.toDouble() }
        val mean = counts.average()
        val variance = counts.map { (it - mean) * (it - mean) }.average()

        val specificityMultiplier = ShadowKeyGenerator.specificityScore(shadowKey).toDouble()
        val locationCoverage = locationCounts.size.toDouble() / totalUserLocations

        val persistenceScore = (1.0 / (1.0 + variance)) * specificityMultiplier * locationCoverage

        // Get devices for rotation analysis
        val devices = deviceRepository.getDevicesByShadowKey(shadowKey)
        val nonWhitelistedDevices = devices.filter { it.id !in whitelistedIds }
        if (nonWhitelistedDevices.isEmpty()) return null

        // Run MAC rotation detection
        val rotationResult = macRotationDetector.detectRotation(nonWhitelistedDevices)

        // Combined score
        val combinedScore = (persistenceScore * PERSISTENCE_WEIGHT) +
            (rotationResult.score * ROTATION_WEIGHT)

        // Pick representative device: most recently seen with highest RSSI
        val representative = nonWhitelistedDevices.maxByOrNull {
            it.lastSeen * 1000 + (it.highestRssi ?: -100)
        } ?: return null

        Timber.tag(TAG).d(
            "Shadow $shadowKey: persistence=%.2f, rotation=%.2f, combined=%.2f, " +
                "locations=${locationCounts.size}, devices=${nonWhitelistedDevices.size}",
            persistenceScore, rotationResult.score, combinedScore
        )

        return ShadowDetectionResult(
            representativeDevice = representative,
            shadowKey = shadowKey,
            persistenceScore = persistenceScore,
            rotationScore = rotationResult.score,
            locationCount = locationCounts.size,
            combinedScore = combinedScore
        )
    }
}
