package com.tailbait.algorithm

import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.UserPath
import com.tailbait.data.model.DetectionResult
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LinkStrength
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.util.DistanceCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core detection algorithm for identifying potentially suspicious BLE devices.
 *
 * The DetectionAlgorithm is the heart of TailBait's stalking detection
 * system. It analyzes BLE scan data to identify devices that exhibit suspicious
 * tracking behavior by:
 *
 * 1. **Multi-Location Detection**: Finding devices that appear at multiple distinct
 *    locations, which suggests the device is following the user rather than being
 *    stationary (like a neighbor's device).
 *
 * 2. **Whitelist Filtering**: Excluding known trusted devices (user's own devices,
 *    partner's devices, etc.) from detection to prevent false positives.
 *
 * 3. **Distance Analysis**: Calculating distances between detection locations to
 *    ensure they're sufficiently far apart to rule out stationary devices.
 *
 * 4. **Time-Based Correlation**: Analyzing temporal patterns to identify persistent
 *    tracking behavior over time periods.
 *
 * 5. **Threshold-Based Detection**: Using user-configurable thresholds for location
 *    count and distance to customize sensitivity.
 *
 * 6. **Threat Scoring**: Assigning a normalized threat score (0.0 - 1.0) to each
 *    suspicious device based on multiple behavioral factors.
 *
 * Algorithm Flow:
 * ```
 * 1. Load user settings (thresholds, preferences)
 * 2. Load whitelist (trusted devices to exclude)
 * 3. Query devices seen at N+ locations (from DeviceRepository)
 * 4. For each potentially suspicious device:
 *    a. Skip if whitelisted
 *    b. Get all locations where device was seen
 *    c. Calculate distances between all location pairs
 *    d. Filter locations by minimum distance threshold
 *    e. Calculate threat score using ThreatScoreCalculator
 *    f. If score >= threshold, create DetectionResult
 * 5. Return results sorted by threat score (highest first)
 * ```
 *
 * Detection Criteria (all must be met):
 * - Device seen at >= alertThresholdCount locations (default: 3)
 * - At least some locations are >= minDetectionDistanceMeters apart (default: 100m)
 * - Device is not in user's whitelist
 * - Threat score >= 0.5 (minimum threshold for alerts)
 *
 * Usage:
 * ```kotlin
 * val detectionAlgorithm = DetectionAlgorithm(...)
 * val results = detectionAlgorithm.runDetection()
 * results.forEach { result ->
 *     // Generate alert, show notification, etc.
 *     println("Suspicious device: ${result.device.name}")
 *     println("Threat score: ${result.threatScore}")
 * }
 * ```
 *
 * Performance Considerations:
 * - This is a database-heavy operation; should run in background worker
 * - Typical execution time: 100-500ms depending on data volume
 * - Recommended execution frequency: Every 15 minutes via WorkManager
 *
 * Thread Safety:
 * - This class is thread-safe and uses suspend functions for async operations
 * - Safe to call from multiple coroutines, but should be serialized via WorkManager
 *
 * @property deviceRepository Repository for accessing device data
 * @property locationRepository Repository for accessing location data (via LocationDao)
 * @property whitelistRepository Repository for accessing whitelist data
 * @property settingsRepository Repository for accessing user settings
 * @property threatScoreCalculator Calculator for threat score computation
 * @property patternMatcher Helper for pattern detection analysis
 */
@Singleton
class DetectionAlgorithm @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val locationRepository: LocationRepository,
    private val whitelistRepository: WhitelistRepository,
    private val settingsRepository: SettingsRepository,
    private val threatScoreCalculator: ThreatScoreCalculator,
    private val patternMatcher: PatternMatcher,
    private val shadowAnalyzer: ShadowAnalyzer
) {

    companion object {
        // Minimum threat score to generate an alert (0.5 = 50%)
        private const val MIN_THREAT_SCORE_FOR_ALERT = 0.5

        // Weak link discount factor
        // Weak links are based on circumstantial evidence (temporal correlation, RSSI similarity)
        // and could be wrong in crowded areas. We discount the threat score by this amount
        // for the proportion of weak links.
        //
        // Example: If 50% of linked devices are weak-linked, apply 15% discount (0.5 * 0.3)
        private const val WEAK_LINK_DISCOUNT_FACTOR = 0.3

        // Tag for logging
        private const val TAG = "DetectionAlgorithm"
    }

    /**
     * Run the detection algorithm to identify suspicious devices.
     *
     * This is the main entry point for the detection system. It should be called
     * periodically (e.g., every 15 minutes) by a WorkManager worker to analyze
     * accumulated scan data and generate alerts for suspicious devices.
     *
     * The algorithm uses user-configured settings for thresholds and excludes
     * whitelisted devices from analysis. Results are sorted by threat score
     * in descending order (most suspicious first).
     *
     * All database operations run on IO dispatcher for optimal performance.
     *
     * @return List of detection results for suspicious devices, sorted by threat score
     * @throws Exception if database access fails or settings are unavailable
     */
    suspend fun runDetection(): List<DetectionResult> = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("Starting detection algorithm")
        val startTime = System.currentTimeMillis()

        try {
            // Step 1: Load user settings
            val settings = settingsRepository.getSettings().first()
            Timber.tag(TAG).d(
                "Loaded settings: alertThreshold=${settings.alertThresholdCount}, " +
                    "minDistance=${settings.minDetectionDistanceMeters}"
            )

            // Step 2: Load whitelisted device IDs as a Set for O(1) lookup
            val whitelistedDeviceIds = whitelistRepository.getAllWhitelistedDeviceIds().first().toSet()
            Timber.tag(TAG).d("Loaded ${whitelistedDeviceIds.size} whitelisted devices")

            // Step 3: Get devices seen at multiple locations (pre-filtered by DAO)
            // Uses fingerprint-aware query that aggregates locations across MAC rotations
            // for the same physical device (e.g., AirTags that rotate MAC every ~15 min)
            val suspiciousDevices = deviceRepository.getSuspiciousDevicesWithLinked(
                minLocationCount = settings.alertThresholdCount
            ).first()
            Timber.tag(TAG).d(
                "Found ${suspiciousDevices.size} devices at " +
                    "${settings.alertThresholdCount}+ locations"
            )

            // Step 4: Filter out whitelisted devices early (before expensive operations)
            val nonWhitelistedDevices = suspiciousDevices.filter { device ->
                val isWhitelisted = device.id in whitelistedDeviceIds
                if (isWhitelisted) {
                    Timber.tag(TAG).v("Skipping whitelisted device: ${device.address}")
                }
                !isWhitelisted
            }

            if (nonWhitelistedDevices.isEmpty()) {
                val elapsedTime = System.currentTimeMillis() - startTime
                Timber.tag(TAG).i("Detection complete: 0 alerts (all devices whitelisted) in ${elapsedTime}ms")
                return@withContext emptyList()
            }

            // Step 5: Load user location history AND movement path for correlation
            // userLocations (Places) provides context, userPaths provides granular movement sequence
            val userLocations = locationRepository.getAllLocations().first()
            val userPaths = locationRepository.getUserPathSince(0) // Load all history for now
            Timber.tag(TAG).d("Loaded ${userLocations.size} user locations and ${userPaths.size} path points for correlation analysis")

            // Step 6: Batch load locations and device records for all devices concurrently
            val deviceDataMap = coroutineScope {
                nonWhitelistedDevices.map { device ->
                    async {
                        val locations = getLocationsForDevice(device.id)
                        val deviceRecords = deviceRepository.getDeviceLocationRecordsForDevice(device.id)
                        device.id to Pair(locations, deviceRecords)
                    }
                }.awaitAll().toMap()
            }

            // Step 7: Analyze each potentially suspicious device with ENHANCED scoring
            val results = mutableListOf<DetectionResult>()

            for (device in nonWhitelistedDevices) {
                val (locations, deviceRecords) = deviceDataMap[device.id] ?: continue

                // Verify location count (defensive check, should already be filtered)
                if (locations.size < settings.alertThresholdCount) {
                    Timber.tag(TAG).w(
                        "Device ${device.address} has only ${locations.size} locations, " +
                            "expected ${settings.alertThresholdCount}+"
                    )
                    continue
                }

                // Calculate distances between all location pairs
                val distances = calculateDistancesBetweenLocations(locations)

                // Early termination: Check if ANY distance exceeds threshold
                val hasSignificantDistance = distances.any { it >= settings.minDetectionDistanceMeters }

                if (!hasSignificantDistance) {
                    Timber.tag(TAG).v(
                        "Device ${device.address} has no significant distances " +
                            "(all < ${settings.minDetectionDistanceMeters}m)"
                    )
                    continue
                }

                // Calculate ENHANCED threat score with movement correlation
                // This is the key improvement: analyze if device moves when user moves
                var threatScore = threatScoreCalculator.calculateEnhanced(
                    device = device,
                    locations = locations,
                    distances = distances,
                    deviceRecords = deviceRecords,
                    userLocations = userLocations,
                    userPaths = userPaths
                )

                // Apply weak link discount
                // If this device's detection relies on weak-linked devices (temporal correlation only),
                // reduce the threat score since there's higher uncertainty about device identity
                val linkedDevices = deviceRepository.getLinkedDevices(device.id)
                val weakLinkDiscount = calculateWeakLinkDiscount(linkedDevices)
                if (weakLinkDiscount > 0.0) {
                    val originalScore = threatScore
                    threatScore *= (1.0 - weakLinkDiscount)
                    Timber.tag(TAG).d(
                        "Applied weak link discount: ${linkedDevices.size} linked devices, " +
                        "${(weakLinkDiscount * 100).toInt()}% discount, " +
                        "score ${"%.2f".format(originalScore)} → ${"%.2f".format(threatScore)}"
                    )
                }

                // Enhanced logging with Find My separated status
                val findMyStatus = if (device.findMySeparated) " [SEPARATED FROM OWNER!]" else ""
                Timber.tag(TAG).d(
                    "Device ${device.address}: ${locations.size} locations, " +
                        "max distance ${distances.maxOrNull()?.toInt()}m, " +
                        "threat score ${"%.2f".format(threatScore)}$findMyStatus"
                )

                // Generate detection result if score exceeds threshold
                if (threatScore >= MIN_THREAT_SCORE_FOR_ALERT) {
                    val result = DetectionResult(
                        device = device,
                        locations = locations,
                        threatScore = threatScore,
                        maxDistance = distances.maxOrNull() ?: 0.0,
                        avgDistance = if (distances.isNotEmpty()) distances.average() else 0.0,
                        detectionReason = buildDetectionReason(device, locations, threatScore)
                    )
                    results.add(result)
                    Timber.tag(TAG).i("ALERT: ${result.detectionReason}")
                }
            }

            // Step 8: Shadow-based detection (parallel path for MAC-agnostic detection)
            // Finds suspicious device profiles even when explicit MAC linking failed
            val existingDeviceIds = results.map { it.device.id }.toSet()
            try {
                val shadowResults = shadowAnalyzer.findSuspiciousShadows(
                    minLocationCount = settings.alertThresholdCount,
                    whitelistedIds = whitelistedDeviceIds
                )

                for (shadowResult in shadowResults) {
                    val device = shadowResult.representativeDevice

                    // Dedup: skip if this device was already detected by MAC-linked path
                    if (device.id in existingDeviceIds) {
                        Timber.tag(TAG).d(
                            "Shadow result for ${device.address} already detected by MAC-linked path, skipping"
                        )
                        continue
                    }

                    // Get locations for this representative device
                    val locations = getLocationsForDevice(device.id)
                    if (locations.size < settings.alertThresholdCount) continue

                    val distances = calculateDistancesBetweenLocations(locations)
                    if (distances.none { it >= settings.minDetectionDistanceMeters }) continue

                    // Compute base threat score, then blend with shadow persistence score
                    val deviceRecords = deviceRepository.getDeviceLocationRecordsForDevice(device.id)
                    val baseThreatScore = threatScoreCalculator.calculateEnhanced(
                        device = device,
                        locations = locations,
                        distances = distances,
                        deviceRecords = deviceRecords,
                        userLocations = userLocations,
                        userPaths = userPaths
                    )

                    // Blend: average of base threat score and shadow combined score
                    val blendedScore = (baseThreatScore + shadowResult.combinedScore) / 2.0

                    if (blendedScore >= MIN_THREAT_SCORE_FOR_ALERT) {
                        val result = DetectionResult(
                            device = device,
                            locations = locations,
                            threatScore = blendedScore,
                            maxDistance = distances.maxOrNull() ?: 0.0,
                            avgDistance = if (distances.isNotEmpty()) distances.average() else 0.0,
                            detectionReason = buildDetectionReason(device, locations, blendedScore) +
                                " (Shadow detection: persistence=${"%.2f".format(shadowResult.persistenceScore)}" +
                                ", rotation=${"%.2f".format(shadowResult.rotationScore)})"
                        )
                        results.add(result)
                        Timber.tag(TAG).i("SHADOW ALERT: ${result.detectionReason}")
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Shadow detection failed, continuing with MAC-linked results only")
            }

            // Sort results by threat score (highest first)
            val sortedResults = results.sortedByDescending { it.threatScore }

            val elapsedTime = System.currentTimeMillis() - startTime
            Timber.tag(TAG).i("Detection complete: ${sortedResults.size} alerts generated in ${elapsedTime}ms")

            sortedResults

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error running detection algorithm")
            throw e
        }
    }

    /**
     * Get all locations where a device was detected, INCLUDING linked devices.
     *
     * This is the fix for the correlation bug: we must fetch locations for
     * the primary device AND any devices linked to it (e.g. via MAC rotation).
     *
     * @param deviceId Device ID
     * @return List of locations where device was seen
     */
    private suspend fun getLocationsForDevice(deviceId: Long): List<Location> {
        return locationRepository.getLocationsForDeviceWithLinked(deviceId)
    }

    /**
     * Calculate distances between all pairs of locations.
     *
     * Uses the Haversine formula (via DistanceCalculator) to compute great-circle
     * distances between all location pairs. This generates a list of all pairwise
     * distances, which is used for:
     * - Checking if locations are sufficiently far apart
     * - Finding maximum distance (for threat scoring)
     * - Computing average distance (for threat scoring)
     *
     * For N locations, this generates N*(N-1)/2 distances.
     * Example: 5 locations = 10 distances
     *
     * @param locations List of locations
     * @return List of distances in meters between all location pairs
     */
    private fun calculateDistancesBetweenLocations(locations: List<Location>): List<Double> {
        val distances = mutableListOf<Double>()

        for (i in locations.indices) {
            for (j in i + 1 until locations.size) {
                val distance = DistanceCalculator.calculateDistance(
                    locations[i].latitude,
                    locations[i].longitude,
                    locations[j].latitude,
                    locations[j].longitude
                )
                distances.add(distance)
            }
        }

        return distances
    }

    /**
     * Build a human-readable explanation of why a device was flagged.
     *
     * This message is shown to users in alert notifications and the alerts UI.
     * It provides context about:
     * - Device identification (name or MAC address)
     * - Number of locations where it was detected
     * - Time span of detections
     * - Threat level assessment
     *
     * Example outputs:
     * - "Device iPhone detected at 5 different locations over the last 2 days. Threat level: HIGH."
     * - "Device AA:BB:CC:DD:EE:FF detected at 3 different locations over the last 6 hours. Threat level: MEDIUM."
     *
     * @param device The suspicious device
     * @param locations List of locations where device was seen
     * @param threatScore Calculated threat score
     * @return Human-readable detection reason
     */
    private fun buildDetectionReason(
        device: ScannedDevice,
        locations: List<Location>,
        threatScore: Double
    ): String {
        return buildString {
            // Device identification
            val deviceName = device.name?.takeIf { it.isNotBlank() } ?: device.address
            append("Device ")
            append(deviceName)
            append(" detected at ")

            // Location count
            append(locations.size)
            append(" different location")
            if (locations.size != 1) append("s")
            append(" ")

            // Time span
            append("over ")
            append(calculateTimeSpanString(locations))
            append(". ")

            // CRITICAL: Find My separated status warning
            // This is a strong indicator of potential stalking
            if (device.findMySeparated && device.isTracker) {
                append("WARNING: This tracker is SEPARATED from its owner! ")
            } else if (device.findMySeparated) {
                append("Note: Device separated from owner. ")
            }

            // Threat level
            append("Threat level: ")
            append(getThreatLevelString(threatScore))
            append(".")
        }
    }

    /**
     * Calculate a human-readable time span string from locations.
     *
     * @param locations List of locations
     * @return Time span string (e.g., "the last 2 hours", "the last 3 days")
     */
    private fun calculateTimeSpanString(locations: List<Location>): String {
        if (locations.isEmpty()) return "unknown time"

        val oldest = locations.minByOrNull { it.timestamp }?.timestamp ?: return "unknown time"
        val newest = locations.maxByOrNull { it.timestamp }?.timestamp ?: return "unknown time"
        val diffMs = newest - oldest

        val diffHours = diffMs / (1000 * 60 * 60)

        return when {
            diffHours < 1 -> "the last hour"
            diffHours < 24 -> {
                "the last $diffHours hour${if (diffHours != 1L) "s" else ""}"
            }
            else -> {
                val days = diffHours / 24
                "the last $days day${if (days != 1L) "s" else ""}"
            }
        }
    }

    /**
     * Get threat level string from threat score.
     *
     * @param score Threat score (0.0 - 1.0)
     * @return Threat level string
     */
    private fun getThreatLevelString(score: Double): String {
        return when {
            score >= 0.9 -> "CRITICAL"
            score >= 0.75 -> "HIGH"
            score >= 0.6 -> "MEDIUM"
            else -> "LOW"
        }
    }

    /**
     * Calculate the discount factor to apply for weak-linked devices.
     *
     * When devices are linked via temporal correlation (weak links), there's a higher
     * chance the links are incorrect, especially in crowded areas where multiple
     * similar devices might exist. This method calculates a discount factor based
     * on the proportion of weak links.
     *
     * ## Link Strength Categories
     * - STRONG: Based on stable identifiers (fingerprint, device name match)
     *   High confidence - no discount applied
     * - WEAK: Based on circumstantial evidence (temporal proximity, RSSI similarity)
     *   Lower confidence - discount applied
     *
     * ## Discount Calculation
     * The discount is proportional to the ratio of weak-linked devices:
     * discount = (weakCount / totalLinked) * WEAK_LINK_DISCOUNT_FACTOR
     *
     * Example with WEAK_LINK_DISCOUNT_FACTOR = 0.3:
     * - 0 linked devices: no discount (0%)
     * - 5 linked, all STRONG: no discount (0%)
     * - 5 linked, 2 WEAK: 12% discount (2/5 * 0.3)
     * - 5 linked, all WEAK: 30% discount (5/5 * 0.3)
     *
     * @param linkedDevices List of devices linked to the primary device
     * @return Discount factor to apply (0.0 to WEAK_LINK_DISCOUNT_FACTOR)
     */
    private fun calculateWeakLinkDiscount(linkedDevices: List<ScannedDevice>): Double {
        // Primary device (id matches, not linked) doesn't have linkStrength
        // Only consider devices that are actually linked (have linkedDeviceId set)
        val actuallyLinkedDevices = linkedDevices.filter { it.linkedDeviceId != null }

        if (actuallyLinkedDevices.isEmpty()) {
            return 0.0  // No linked devices, no discount
        }

        // Count weak links
        val weakLinkCount = actuallyLinkedDevices.count {
            it.linkStrength == LinkStrength.WEAK.name
        }

        // Calculate proportion of weak links
        val weakProportion = weakLinkCount.toDouble() / actuallyLinkedDevices.size

        // Apply discount factor
        return weakProportion * WEAK_LINK_DISCOUNT_FACTOR
    }

    /**
     * Run detection for a specific device (for testing/debugging).
     *
     * This method allows running the detection algorithm on a single device
     * rather than all devices. Useful for:
     * - Testing detection logic on specific devices
     * - Manual verification of device suspiciousness
     * - Debugging false positives/negatives
     *
     * @param deviceId ID of device to analyze
     * @return DetectionResult if device is suspicious, null otherwise
     */
    suspend fun runDetectionForDevice(deviceId: Long): DetectionResult? = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("Running detection for specific device: $deviceId")

        try {
            val settings = settingsRepository.getSettings().first()
            val whitelistedDeviceIds = whitelistRepository.getAllWhitelistedDeviceIds().first().toSet()

            // Check if device is whitelisted
            if (deviceId in whitelistedDeviceIds) {
                Timber.tag(TAG).d("Device $deviceId is whitelisted")
                return@withContext null
            }

            // Get device by ID directly (optimized - no need to load all devices)
            val device = deviceRepository.getDeviceById(deviceId)
                ?: run {
                    Timber.tag(TAG).w("Device $deviceId not found")
                    return@withContext null
                }

            // Get locations
            val locations = getLocationsForDevice(deviceId)

            if (locations.size < settings.alertThresholdCount) {
                Timber.tag(TAG).d(
                    "Device $deviceId has insufficient locations: " +
                        "${locations.size} < ${settings.alertThresholdCount}"
                )
                return@withContext null
            }

            // Calculate distances
            val distances = calculateDistancesBetweenLocations(locations)

            // Early termination: Check if ANY distance exceeds threshold
            val hasSignificantDistance = distances.any { it >= settings.minDetectionDistanceMeters }

            if (!hasSignificantDistance) {
                Timber.tag(TAG).d("Device $deviceId has no significant distances")
                return@withContext null
            }

            // Load data for enhanced scoring
            val deviceRecords = deviceRepository.getDeviceLocationRecordsForDevice(deviceId)
            val userLocations = locationRepository.getAllLocations().first()
            val userPaths = locationRepository.getUserPathSince(0)

            // Calculate ENHANCED threat score with movement correlation
            var threatScore = threatScoreCalculator.calculateEnhanced(
                device = device,
                locations = locations,
                distances = distances,
                deviceRecords = deviceRecords,
                userLocations = userLocations,
                userPaths = userPaths
            )

            // Apply weak link discount (same logic as runDetection)
            val linkedDevices = deviceRepository.getLinkedDevices(deviceId)
            val weakLinkDiscount = calculateWeakLinkDiscount(linkedDevices)
            if (weakLinkDiscount > 0.0) {
                threatScore *= (1.0 - weakLinkDiscount)
                Timber.tag(TAG).d(
                    "Applied weak link discount: ${(weakLinkDiscount * 100).toInt()}% discount"
                )
            }

            if (threatScore < MIN_THREAT_SCORE_FOR_ALERT) {
                Timber.tag(TAG).d("Device $deviceId threat score too low: $threatScore")
                return@withContext null
            }

            DetectionResult(
                device = device,
                locations = locations,
                threatScore = threatScore,
                maxDistance = distances.maxOrNull() ?: 0.0,
                avgDistance = if (distances.isNotEmpty()) distances.average() else 0.0,
                detectionReason = buildDetectionReason(device, locations, threatScore)
            )

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error running detection for device $deviceId")
            null
        }
    }
}
