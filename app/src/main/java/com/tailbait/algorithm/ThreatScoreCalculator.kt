package com.tailbait.algorithm

import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.UserPath
import com.tailbait.data.model.ThreatScoreBreakdown
import com.tailbait.util.SignalStrength
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Calculator for determining threat scores for potentially suspicious devices.
 *
 * The ThreatScoreCalculator implements a multi-factor scoring algorithm that
 * analyzes various aspects of device detection patterns to assess the likelihood
 * of stalking behavior. The score is a weighted combination of multiple factors:
 *
 * ## Enhanced Scoring Factors (v3.0 with Signal Strength)
 *
 * 1. **Movement Correlation Factor (25%)**: The most important factor.
 *    Analyzes whether device movement patterns correlate with user movement.
 *    A device that moves exactly when you move is the "smoking gun" for stalking.
 *
 * 2. **Location Count Factor (18%)**: Devices seen at many distinct locations
 *    are more suspicious, as legitimate nearby devices typically appear in only
 *    a few locations.
 *
 * 3. **Device Type Factor (15%)**: Trackers (AirTag, Tile) and separated Find My
 *    devices get highest scores. Includes bonus for Find My devices that are
 *    separated from their owner.
 *
 * 4. **Signal Strength Factor (12%)**: NEW! Learned from Nordic BLE patterns.
 *    Devices that have been detected very close (strong RSSI) are more suspicious.
 *    Uses 5-level classification: VERY_WEAK, WEAK, MEDIUM, STRONG, VERY_STRONG.
 *    A tracker in someone's bag will consistently show strong signal.
 *
 * 5. **Distance Factor (12%)**: Greater distances between detection locations
 *    indicate following behavior.
 *
 * 6. **Consistency Factor (10%)**: Regular, periodic appearances suggest
 *    deliberate tracking rather than random coincidence.
 *
 * 7. **Time Correlation Factor (8%)**: Longer tracking periods indicate
 *    persistent following.
 *
 * The final threat score ranges from 0.0 (no threat) to 1.0 (maximum threat),
 * and can be categorized into threat levels:
 * - 0.9+: CRITICAL - Very high confidence of stalking
 * - 0.75+: HIGH - High confidence of stalking
 * - 0.6+: MEDIUM - Moderate confidence of stalking
 * - 0.5+: LOW - Low confidence but worth flagging
 *
 * Usage:
 * ```kotlin
 * val calculator = ThreatScoreCalculator(movementCorrelationCalculator)
 * val score = calculator.calculate(device, locations, distances)
 * val enhancedScore = calculator.calculateEnhanced(device, locations, distances, deviceRecords, userLocations)
 * ```
 *
 * Thread Safety:
 * This class is stateless and thread-safe.
 */
@Singleton
class ThreatScoreCalculator @Inject constructor(
    private val movementCorrelationCalculator: MovementCorrelationCalculator
) {

    companion object {
        // Enhanced scoring weights (must sum to 1.0)
        private const val WEIGHT_MOVEMENT_CORRELATION = 0.25  // Most important factor
        private const val WEIGHT_LOCATION_COUNT = 0.18        // Reduced to make room for signal strength
        private const val WEIGHT_DISTANCE = 0.12              // Reduced from 0.15
        private const val WEIGHT_DEVICE_TYPE = 0.15           // Includes separated bonus
        private const val WEIGHT_SIGNAL_STRENGTH = 0.12       // NEW! Proximity-based threat (from Nordic patterns)
        private const val WEIGHT_TIME_CORRELATION = 0.08      // Reduced from 0.10
        private const val WEIGHT_CONSISTENCY = 0.10           // Unchanged

        // Legacy weights for backward compatibility
        private const val LEGACY_WEIGHT_LOCATION_COUNT = 0.3
        private const val LEGACY_WEIGHT_DISTANCE = 0.25
        private const val LEGACY_WEIGHT_TIME_CORRELATION = 0.2
        private const val LEGACY_WEIGHT_CONSISTENCY = 0.15
        private const val LEGACY_WEIGHT_DEVICE_TYPE = 0.1

        // Normalization constants
        private const val MAX_LOCATIONS_FOR_NORMALIZATION = 10.0 // 10+ locations = max score
        private const val MAX_DISTANCE_FOR_NORMALIZATION = 10000.0 // 10km = max score

        // Time thresholds (in milliseconds)
        private const val ONE_HOUR_MS = 3600000L
        private const val SIX_HOURS_MS = 21600000L
        private const val ONE_DAY_MS = 86400000L

        // Device type scores (enhanced for comprehensive device identification)
        private const val SCORE_TRACKER = 0.1         // Highest: AirTag, Tile, SmartTag, Chipolo
        private const val SCORE_PHONE_TABLET = 0.1    // High: Active user devices
        private const val SCORE_WATCH = 0.08          // Medium-high: Wearables with GPS
        private const val SCORE_FITNESS_BAND = 0.06   // Medium: Fitness trackers
        private const val SCORE_EARBUDS = 0.05        // Lower: Usually stay with owner
        private const val SCORE_HEADPHONES = 0.04     // Lower: Less mobile
        private const val SCORE_SPEAKER = 0.03        // Low: Usually stationary
        private const val SCORE_BEACON = 0.02         // Lowest: Fixed location
        private const val SCORE_OTHER = 0.05          // Default moderate
    }

    /**
     * Calculate the threat score for a device based on its detection pattern.
     * Uses LEGACY weights for backward compatibility.
     *
     * @param device The scanned device
     * @param locations List of locations where the device was detected
     * @param distances List of distances between location pairs
     * @return Threat score between 0.0 and 1.0
     */
    fun calculate(
        device: ScannedDevice,
        locations: List<Location>,
        distances: List<Double>
    ): Double {
        var totalScore = 0.0

        // Factor 1: Number of distinct locations (0.0 - 0.3)
        totalScore += calculateLocationCountScoreLegacy(locations)

        // Factor 2: Maximum distance between locations (0.0 - 0.25)
        totalScore += calculateDistanceScoreLegacy(distances)

        // Factor 3: Time correlation (0.0 - 0.2)
        totalScore += calculateTimeCorrelationScoreLegacy(locations)

        // Factor 4: Consistency of appearances (0.0 - 0.15)
        totalScore += calculateConsistencyScoreLegacy(locations)

        // Factor 5: Device type suspicion (0.0 - 0.1)
        totalScore += calculateDeviceTypeScoreLegacy(device)

        // Ensure score doesn't exceed 1.0 due to floating point arithmetic
        return min(totalScore, 1.0)
    }

    /**
     * ENHANCED threat score calculation with movement correlation.
     * This is the recommended method for stalking detection as it includes
     * the critical movement synchronization analysis.
     *
     * @param device The scanned device
     * @param locations List of locations where the device was detected
     * @param distances List of distances between location pairs
     * @param deviceRecords Device-location records (for movement analysis)
     * @param deviceRecords Device-location records (for movement analysis)
     * @param userLocations User's location history (for legacy fallback and context)
     * @param userPaths User's granular movement history (for accurate correlation)
     * @return Threat score between 0.0 and 1.0
     */
    fun calculateEnhanced(
        device: ScannedDevice,
        locations: List<Location>,
        distances: List<Double>,
        deviceRecords: List<DeviceLocationRecord>,
        userLocations: List<Location>,
        userPaths: List<UserPath>
    ): Double {
        var totalScore = 0.0

        // Factor 1: Movement Correlation (0.0 - 0.25) - THE SMOKING GUN!
        val correlationScore = movementCorrelationCalculator.calculateCorrelation(
            deviceRecords = deviceRecords,
            userPaths = userPaths,
            deviceLocations = locations
        )

        totalScore += correlationScore * WEIGHT_MOVEMENT_CORRELATION

        // Factor 2: Number of distinct locations (0.0 - 0.18)
        val locationScore = min(locations.size / MAX_LOCATIONS_FOR_NORMALIZATION, 1.0)
        totalScore += locationScore * WEIGHT_LOCATION_COUNT

        // Factor 3: Device type with Find My separated bonus (0.0 - 0.15)
        totalScore += calculateEnhancedDeviceTypeScore(device)

        // Factor 4: Signal Strength (0.0 - 0.12) - NEW! From Nordic BLE patterns
        // A device that's been very close is more suspicious
        totalScore += calculateSignalStrengthScore(device)

        // Factor 5: Maximum distance between locations (0.0 - 0.12)
        if (distances.isNotEmpty()) {
            val maxDistance = distances.maxOrNull() ?: 0.0
            val distanceScore = min(maxDistance / MAX_DISTANCE_FOR_NORMALIZATION, 1.0)
            totalScore += distanceScore * WEIGHT_DISTANCE
        }

        // Factor 6: Consistency of appearances (0.0 - 0.10)
        val avgTimeBetween = calculateAverageTimeBetween(locations)
        val consistencyScore = when {
            avgTimeBetween < ONE_HOUR_MS -> 1.0      // Very regular, highly suspicious
            avgTimeBetween < SIX_HOURS_MS -> 0.67
            avgTimeBetween < ONE_DAY_MS -> 0.33
            else -> 0.13
        }
        totalScore += consistencyScore * WEIGHT_CONSISTENCY

        // Factor 7: Time correlation (0.0 - 0.08)
        val timeSpan = calculateTimeSpan(locations)
        val timeScore = when {
            timeSpan < ONE_HOUR_MS -> 0.25  // Short period, low score
            timeSpan < ONE_DAY_MS -> 0.75   // Medium period
            else -> 1.0                      // Long tracking period, max score
        }
        totalScore += timeScore * WEIGHT_TIME_CORRELATION

        return min(totalScore, 1.0)
    }

    /**
     * Enhanced device type scoring with Find My separated bonus.
     * Separated Find My devices (AirTags away from owner) are EXTREMELY suspicious.
     */
    private fun calculateEnhancedDeviceTypeScore(device: ScannedDevice): Double {
        var score = 0.0

        // Base score from device type
        val baseScore = when {
            device.isTracker -> 1.0
            device.deviceType?.uppercase() == "TRACKER" -> 1.0
            device.deviceType?.uppercase() in listOf("PHONE", "TABLET") -> 0.8
            device.deviceType?.uppercase() == "WATCH" -> 0.6
            device.deviceType?.uppercase() == "FITNESS_BAND" -> 0.4
            else -> 0.3
        }
        score = baseScore * 0.7  // 70% of device type weight from base type

        // CRITICAL: Separated Find My bonus (30% of device type weight)
        // A separated AirTag following you is the strongest indicator of stalking
        if (device.findMySeparated && device.isTracker) {
            score += 0.3  // Add 30% bonus for separated tracker
        } else if (device.findMySeparated) {
            score += 0.15  // 15% bonus for separated non-tracker
        }

        return min(score, 1.0) * WEIGHT_DEVICE_TYPE
    }

    /**
     * Calculate score based on signal strength / proximity.
     * Learned from Nordic nRF-Connect-Device-Manager patterns.
     *
     * A device that has been detected with strong signal (close proximity)
     * is more suspicious than one always detected far away.
     * Uses highestRssi to capture the closest the device has ever been.
     *
     * ## Scoring Logic
     * - VERY_STRONG (< 1m): 1.0 - Device was in user's bag/pocket/car
     * - STRONG (1-2m): 0.75 - Very close, likely intentionally placed nearby
     * - MEDIUM (2-5m): 0.5 - Close proximity, same room
     * - WEAK (5-10m): 0.25 - Moderate distance, could be coincidence
     * - VERY_WEAK (10m+): 0.1 - Far away, low suspicion
     *
     * Additionally considers the stored signalStrength classification
     * for backward compatibility and cases where highestRssi isn't available.
     *
     * Score range: 0.0 - WEIGHT_SIGNAL_STRENGTH (0.12)
     *
     * @param device The scanned device with RSSI data
     * @return Signal strength score weighted for total threat calculation
     */
    private fun calculateSignalStrengthScore(device: ScannedDevice): Double {
        // Use highestRssi if available (best indicator of closest proximity)
        val rssiScore = device.highestRssi?.let { rssi ->
            SignalStrength.fromRssi(rssi).threatWeight.toDouble()
        } ?: run {
            // Fallback: use stored signalStrength classification
            device.signalStrength?.let { strengthName ->
                try {
                    SignalStrength.valueOf(strengthName).threatWeight.toDouble()
                } catch (e: IllegalArgumentException) {
                    0.3 // Default moderate score if parsing fails
                }
            } ?: 0.3 // Default moderate score if no signal data
        }

        return rssiScore * WEIGHT_SIGNAL_STRENGTH
    }

    /**
     * Calculate the threat score with detailed breakdown of all factors.
     *
     * This method is useful for debugging, analytics, and showing users
     * why a device was flagged.
     *
     * @param device The scanned device
     * @param locations List of locations where the device was detected
     * @param distances List of distances between location pairs
     * @return Detailed breakdown of the threat score
     */
    fun calculateWithBreakdown(
        device: ScannedDevice,
        locations: List<Location>,
        distances: List<Double>
    ): ThreatScoreBreakdown {
        val locationScore = calculateLocationCountScoreLegacy(locations)
        val distanceScore = calculateDistanceScoreLegacy(distances)
        val timeScore = calculateTimeCorrelationScoreLegacy(locations)
        val consistencyScore = calculateConsistencyScoreLegacy(locations)
        val deviceTypeScore = calculateDeviceTypeScoreLegacy(device)
        val totalScore = min(
            locationScore + distanceScore + timeScore + consistencyScore + deviceTypeScore,
            1.0
        )

        return ThreatScoreBreakdown(
            locationCountScore = locationScore,
            distanceScore = distanceScore,
            timeCorrelationScore = timeScore,
            consistencyScore = consistencyScore,
            deviceTypeScore = deviceTypeScore,
            totalScore = totalScore
        )
    }

    /**
     * Factor 1 (Legacy): Calculate score based on number of distinct locations.
     *
     * More locations indicate a device that is following the user to many
     * different places, which is highly suspicious. We normalize by dividing
     * by MAX_LOCATIONS_FOR_NORMALIZATION (10), so 10+ locations gives max score.
     *
     * Score range: 0.0 - 0.3
     *
     * @param locations List of locations
     * @return Location count score (0.0 - 0.3)
     */
    private fun calculateLocationCountScoreLegacy(locations: List<Location>): Double {
        val normalizedCount = min(locations.size / MAX_LOCATIONS_FOR_NORMALIZATION, 1.0)
        return normalizedCount * LEGACY_WEIGHT_LOCATION_COUNT
    }

    /**
     * Factor 2 (Legacy): Calculate score based on maximum distance between locations.
     *
     * Greater distances between detections indicate that the device is following
     * the user across significant geographic areas.
     *
     * Score range: 0.0 - 0.25
     *
     * @param distances List of distances between location pairs
     * @return Distance score (0.0 - 0.25)
     */
    private fun calculateDistanceScoreLegacy(distances: List<Double>): Double {
        if (distances.isEmpty()) return 0.0
        val maxDistance = distances.maxOrNull() ?: 0.0
        val normalizedDistance = min(maxDistance / MAX_DISTANCE_FOR_NORMALIZATION, 1.0)
        return normalizedDistance * LEGACY_WEIGHT_DISTANCE
    }

    /**
     * Factor 3 (Legacy): Calculate score based on time correlation.
     *
     * Longer tracking periods indicate persistent following behavior.
     *
     * Score range: 0.0 - 0.2
     *
     * @param locations List of locations
     * @return Time correlation score (0.0 - 0.2)
     */
    private fun calculateTimeCorrelationScoreLegacy(locations: List<Location>): Double {
        val timeSpan = calculateTimeSpan(locations)

        return when {
            timeSpan < ONE_HOUR_MS -> 0.05 * LEGACY_WEIGHT_TIME_CORRELATION / 0.2
            timeSpan < ONE_DAY_MS -> 0.15 * LEGACY_WEIGHT_TIME_CORRELATION / 0.2
            else -> LEGACY_WEIGHT_TIME_CORRELATION
        }
    }

    /**
     * Factor 4 (Legacy): Calculate score based on consistency of appearances.
     *
     * Regular, frequent sightings suggest deliberate tracking rather than
     * random coincidence.
     *
     * Score range: 0.0 - 0.15
     *
     * @param locations List of locations
     * @return Consistency score (0.0 - 0.15)
     */
    private fun calculateConsistencyScoreLegacy(locations: List<Location>): Double {
        val avgTimeBetween = calculateAverageTimeBetween(locations)

        return when {
            avgTimeBetween < ONE_HOUR_MS -> LEGACY_WEIGHT_CONSISTENCY
            avgTimeBetween < SIX_HOURS_MS -> 0.10 * LEGACY_WEIGHT_CONSISTENCY / 0.15
            avgTimeBetween < ONE_DAY_MS -> 0.05 * LEGACY_WEIGHT_CONSISTENCY / 0.15
            else -> 0.02 * LEGACY_WEIGHT_CONSISTENCY / 0.15
        }
    }

    /**
     * Factor 5 (Legacy): Calculate score based on device type and tracker identification.
     *
     * Trackers (AirTag, Tile, SmartTag, Chipolo) receive the highest score.
     *
     * Score range: 0.0 - 0.1
     *
     * @param device The scanned device
     * @return Device type score (0.0 - 0.1)
     */
    private fun calculateDeviceTypeScoreLegacy(device: ScannedDevice): Double {
        // If device is explicitly identified as a tracker, give maximum score
        if (device.isTracker) {
            return SCORE_TRACKER
        }

        // Score based on device type
        return when (device.deviceType?.uppercase()) {
            "TRACKER" -> SCORE_TRACKER
            "PHONE", "TABLET" -> SCORE_PHONE_TABLET
            "WATCH" -> SCORE_WATCH
            "FITNESS_BAND" -> SCORE_FITNESS_BAND
            "EARBUDS" -> SCORE_EARBUDS
            "HEADPHONES" -> SCORE_HEADPHONES
            "SPEAKER" -> SCORE_SPEAKER
            "BEACON" -> SCORE_BEACON
            "COMPUTER", "SMART_HOME", "AUTOMOTIVE", "GAMING", "MEDICAL" -> SCORE_BEACON
            else -> SCORE_OTHER
        }
    }

    /**
     * Calculate the time span covered by a list of locations using single-pass algorithm.
     *
     * Optimized to find min and max timestamps in a single iteration.
     *
     * @param locations List of locations
     * @return Time span in milliseconds between oldest and newest location
     */
    private fun calculateTimeSpan(locations: List<Location>): Long {
        if (locations.isEmpty()) return 0
        if (locations.size == 1) return 0

        var oldest = Long.MAX_VALUE
        var newest = Long.MIN_VALUE

        // Single-pass to find both min and max timestamps
        for (location in locations) {
            val timestamp = location.timestamp
            if (timestamp < oldest) oldest = timestamp
            if (timestamp > newest) newest = timestamp
        }

        return newest - oldest
    }

    /**
     * Calculate the average time between consecutive location sightings.
     *
     * This helps determine how regularly/consistently the device appears.
     * Lower values indicate more regular appearances.
     *
     * Optimized to calculate gaps and average in a single pass after sorting.
     *
     * @param locations List of locations
     * @return Average time between sightings in milliseconds
     */
    private fun calculateAverageTimeBetween(locations: List<Location>): Long {
        if (locations.size < 2) return Long.MAX_VALUE

        // Sort once
        val sorted = locations.sortedBy { it.timestamp }

        // Calculate sum of gaps in single pass (avoid creating intermediate list)
        var gapSum = 0L
        val gapCount = sorted.size - 1

        for (i in 0 until gapCount) {
            gapSum += sorted[i + 1].timestamp - sorted[i].timestamp
        }

        return if (gapCount > 0) {
            gapSum / gapCount
        } else {
            Long.MAX_VALUE
        }
    }
}
