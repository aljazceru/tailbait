package com.tailbait.algorithm

import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
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
 */
@Singleton
class DetectionAlgorithm
    @Inject
    constructor(
        private val deviceRepository: DeviceRepository,
        private val locationRepository: LocationRepository,
        private val whitelistRepository: WhitelistRepository,
        private val settingsRepository: SettingsRepository,
        private val threatScoreCalculator: ThreatScoreCalculator,
        private val shadowAnalyzer: ShadowAnalyzer,
    ) {
        companion object {
            // Minimum threat score to generate an alert (0.5 = 50%)
            private const val MIN_THREAT_SCORE_FOR_ALERT = 0.5

            // Only consider device records from the last N days for detection
            private const val DETECTION_WINDOW_DAYS = 3L

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
        @Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
        suspend fun runDetection(): List<DetectionResult> =
            withContext(Dispatchers.IO) {
                Timber.tag(TAG).d("Starting detection algorithm")
                val startTime = System.currentTimeMillis()

                try {
                    // Step 1: Load user settings
                    val settings = settingsRepository.getSettings().first()
                    Timber.tag(TAG).d(
                        "Loaded settings: alertThreshold=${settings.alertThresholdCount}, " +
                            "minDistance=${settings.minDetectionDistanceMeters}",
                    )

                    // Step 2: Load whitelisted device IDs as a Set for O(1) lookup
                    val whitelistedDeviceIds = whitelistRepository.getAllWhitelistedDeviceIds().first().toSet()
                    Timber.tag(TAG).d("Loaded ${whitelistedDeviceIds.size} whitelisted devices")

                    // Step 3: Get devices seen at multiple locations within the detection window
                    // Uses fingerprint-aware query that aggregates locations across MAC rotations
                    // for the same physical device (e.g., AirTags that rotate MAC every ~15 min)
                    val sinceTimestamp = System.currentTimeMillis() - (DETECTION_WINDOW_DAYS * 24 * 60 * 60 * 1000L)
                    val suspiciousDevices =
                        deviceRepository.getSuspiciousDevicesWithLinked(
                            minLocationCount = settings.alertThresholdCount,
                            sinceTimestamp = sinceTimestamp,
                        ).first()
                    Timber.tag(TAG).d(
                        "Found ${suspiciousDevices.size} devices at " +
                            "${settings.alertThresholdCount}+ locations",
                    )

                    // Step 4: Filter out whitelisted devices early (before expensive operations)
                    val nonWhitelistedDevices =
                        suspiciousDevices.filter { device ->
                            val isWhitelisted = device.id in whitelistedDeviceIds
                            if (isWhitelisted) {
                                Timber.tag(TAG).v("Skipping whitelisted device: ${device.address}")
                            }
                            !isWhitelisted
                        }

                    // Scope context to the same observation window as device evidence.
                    val userPaths =
                        locationRepository.getUserPathSince(sinceTimestamp).filter { it.timestamp >= sinceTimestamp }
                    val pathLocationIds = userPaths.map { it.locationId }.toSet()
                    val userLocations =
                        locationRepository.getAllLocations().first()
                            .filter { it.timestamp >= sinceTimestamp || it.id in pathLocationIds }
                    Timber.tag(
                        TAG,
                    ).d(
                        "Loaded ${userLocations.size} user locations and ${userPaths.size} path points for correlation analysis",
                    )

                    // Batch-load only eligible observations, then resolve their place coordinates.
                    val deviceDataMap =
                        coroutineScope {
                            nonWhitelistedDevices.map { device ->
                                async {
                                    val records =
                                        deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(
                                            device.id,
                                            sinceTimestamp,
                                        ).filter { it.timestamp >= sinceTimestamp && it.deviceId !in whitelistedDeviceIds }
                                    device.id to Pair(getLocationsForDevice(device.id, records), records)
                                }
                            }.awaitAll().toMap()
                        }

                    // Step 7: Analyze each potentially suspicious device with ENHANCED scoring
                    val results = mutableListOf<DetectionResult>()

                    for (candidate in nonWhitelistedDevices) {
                        val (rawLocations, deviceRecords) = deviceDataMap[candidate.id] ?: continue

                        // Cluster nearby locations to collapse GPS drift / duplicate location IDs
                        val locations = clusterLocations(rawLocations, settings.minDetectionDistanceMeters)
                        if (locations.size < rawLocations.size) {
                            Timber.tag(TAG).d(
                                "Device ${candidate.address}: clustered ${rawLocations.size} locations → ${locations.size}",
                            )
                        }

                        // Verify location count after clustering
                        if (locations.size < settings.alertThresholdCount) {
                            Timber.tag(TAG).w(
                                "Device ${candidate.address} has only ${locations.size} locations, " +
                                    "expected ${settings.alertThresholdCount}+",
                            )
                            continue
                        }

                        // Calculate distances between all location pairs
                        val distances = calculateDistancesBetweenLocations(locations)

                        // Early termination: Check if ANY distance exceeds threshold
                        val hasSignificantDistance = distances.any { it >= settings.minDetectionDistanceMeters }

                        if (!hasSignificantDistance) {
                            Timber.tag(TAG).v(
                                "Device ${candidate.address} has no significant distances " +
                                    "(all < ${settings.minDetectionDistanceMeters}m)",
                            )
                            continue
                        }

                        val device = deviceForScoring(candidate, deviceRecords, sinceTimestamp)
                        val threatScore =
                            threatScoreCalculator.calculateEnhanced(
                                device = device,
                                locations = locations,
                                distances = distances,
                                deviceRecords = deviceRecords,
                                userLocations = userLocations,
                                userPaths = userPaths,
                            )

                        // Enhanced logging with Find My separated status
                        val findMyStatus = if (device.findMySeparated) " [SEPARATED FROM OWNER!]" else ""
                        Timber.tag(TAG).d(
                            "Device ${device.address}: ${locations.size} locations, " +
                                "max distance ${distances.maxOrNull()?.toInt()}m, " +
                                "threat score ${"%.2f".format(threatScore)}$findMyStatus",
                        )

                        // Generate detection result if score exceeds threshold
                        if (threatScore >= MIN_THREAT_SCORE_FOR_ALERT) {
                            val breakdown =
                                threatScoreCalculator.computeBreakdown(
                                    device,
                                    locations,
                                    distances,
                                    deviceRecords,
                                    userLocations,
                                    userPaths,
                                )
                            val result =
                                DetectionResult(
                                    device = device,
                                    locations = locations,
                                    threatScore = threatScore,
                                    maxDistance = distances.maxOrNull() ?: 0.0,
                                    avgDistance = if (distances.isNotEmpty()) distances.average() else 0.0,
                                    detectionReason =
                                        buildDetectionReason(
                                            device,
                                            locations,
                                            threatScore,
                                            deviceRecords,
                                        ),
                                    timeSpanMs =
                                        if (deviceRecords.size >= 2) {
                                            deviceRecords.maxOf { it.timestamp } - deviceRecords.minOf { it.timestamp }
                                        } else {
                                            0L
                                        },
                                    scoreBreakdown = breakdown,
                                )
                            results.add(result)
                            Timber.tag(TAG).i("ALERT: ${result.detectionReason}")
                        }
                    }

                    // Step 8: Shadow-based detection (parallel path for MAC-agnostic detection)
                    // Finds suspicious device profiles even when explicit MAC linking failed
                    val existingDeviceIds = results.map { it.device.id }.toSet()
                    try {
                        val shadowResults =
                            shadowAnalyzer.findSuspiciousShadows(
                                minLocationCount = settings.alertThresholdCount,
                                whitelistedIds = whitelistedDeviceIds,
                                sinceTimestamp = sinceTimestamp,
                            )

                        for (shadowResult in shadowResults) {
                            val device = shadowResult.representativeDevice

                            // Dedup: skip if this device was already detected by MAC-linked path
                            if (device.id in existingDeviceIds) {
                                Timber.tag(TAG).d(
                                    "Shadow result for ${device.address} already detected by MAC-linked path, skipping",
                                )
                                continue
                            }

                            val locations = clusterLocations(shadowResult.locations, settings.minDetectionDistanceMeters)
                            if (locations.size < settings.alertThresholdCount) continue

                            val distances = calculateDistancesBetweenLocations(locations)
                            if (distances.none { it >= settings.minDetectionDistanceMeters }) continue

                            // Compute base threat score, then blend with shadow persistence score
                            val deviceRecords = shadowResult.records
                            val baseThreatScore =
                                threatScoreCalculator.calculateEnhanced(
                                    device = device,
                                    locations = locations,
                                    distances = distances,
                                    deviceRecords = deviceRecords,
                                    userLocations = userLocations,
                                    userPaths = userPaths,
                                )

                            // Blend: average of base threat score and shadow combined score
                            val blendedScore = (baseThreatScore + shadowResult.combinedScore) / 2.0

                            if (blendedScore >= MIN_THREAT_SCORE_FOR_ALERT) {
                                val breakdown =
                                    threatScoreCalculator.computeBreakdown(
                                        device,
                                        locations,
                                        distances,
                                        deviceRecords,
                                        userLocations,
                                        userPaths,
                                    )
                                val result =
                                    DetectionResult(
                                        device = device,
                                        locations = locations,
                                        threatScore = blendedScore,
                                        maxDistance = distances.maxOrNull() ?: 0.0,
                                        avgDistance = if (distances.isNotEmpty()) distances.average() else 0.0,
                                        detectionReason =
                                            buildDetectionReason(device, locations, blendedScore, deviceRecords) +
                                                " (Shadow detection: persistence=${"%.2f".format(shadowResult.persistenceScore)}" +
                                                ", rotation=${"%.2f".format(shadowResult.rotationScore)})",
                                        timeSpanMs =
                                            if (deviceRecords.size >= 2) {
                                                deviceRecords.maxOf { it.timestamp } - deviceRecords.minOf { it.timestamp }
                                            } else {
                                                0L
                                            },
                                        scoreBreakdown = breakdown,
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

        private suspend fun getLocationsForDevice(
            deviceId: Long,
            records: List<DeviceLocationRecord>,
        ): List<Location> {
            val locationIds = records.map { it.locationId }.toSet()
            return locationRepository.getLocationsForDeviceWithLinked(deviceId).filter { it.id in locationIds }
        }

        private suspend fun deviceForScoring(
            device: ScannedDevice,
            records: List<DeviceLocationRecord>,
            sinceTimestamp: Long,
        ): ScannedDevice {
            val rootId = device.linkedDeviceId?.takeIf { device.linkStrength == LinkStrength.STRONG.name } ?: device.id
            val recordDeviceIds = records.map { it.deviceId }.toSet()
            val latestState =
                (deviceRepository.getLinkedDevices(rootId).asSequence() + sequenceOf(device))
                    .filter {
                        it.id in recordDeviceIds &&
                            (it.id == rootId || (it.linkedDeviceId == rootId && it.linkStrength == LinkStrength.STRONG.name)) &&
                            it.findMyStatus != null && (it.findMyStatusTimestamp ?: Long.MIN_VALUE) >= sinceTimestamp
                    }
                    .maxByOrNull { it.findMyStatusTimestamp ?: Long.MIN_VALUE }
            // Keep the canonical identity, but never score stale root state or lifetime signal peaks.
            return device.copy(
                findMyStatus = latestState?.findMyStatus,
                findMyStatusTimestamp = latestState?.findMyStatusTimestamp,
                findMySeparated = latestState?.findMySeparated ?: false,
                highestRssi = records.maxOfOrNull { it.rssi },
            )
        }

        /**
         * Cluster nearby locations into logical places.
         *
         * Uses greedy clustering: iterate valid fixes sorted by timestamp and keep a
         * new place only when its uncertainty circle is separated from every existing
         * representative by at least [radiusMeters].
         *
         * This prevents GPS drift and location dedup failures from inflating the
         * location count for devices that are actually stationary.
         *
         * @param locations Raw locations to cluster
         * @param radiusMeters Maximum distance to merge into existing cluster
         * @return Deduplicated list of representative locations (one per cluster)
         */
        private fun clusterLocations(
            locations: List<Location>,
            radiusMeters: Double,
        ): List<Location> {
            val sorted = locations.filter { it.accuracy.isFinite() && it.accuracy > 0f }.sortedBy { it.timestamp }
            val clusters = mutableListOf<Location>()

            for (location in sorted) {
                val overlapsExistingPlace =
                    clusters.any {
                        val separation =
                            DistanceCalculator.minimumSeparationMeters(
                                DistanceCalculator.calculateDistance(location, it),
                                location.accuracy,
                                it.accuracy,
                            )
                        separation <= 0.0 || separation < radiusMeters
                    }
                if (!overlapsExistingPlace) clusters.add(location)
            }

            return clusters
        }

        /**
         * Calculate distances between all pairs of locations.
         *
         * Subtracts both fixes' accuracy radii from the great-circle distance.
         * This generates conservative pairwise separation bounds, which are used for:
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
                    val distance =
                        DistanceCalculator.minimumSeparationMeters(
                            DistanceCalculator.calculateDistance(locations[i], locations[j]),
                            locations[i].accuracy,
                            locations[j].accuracy,
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
            threatScore: Double,
            deviceRecords: List<com.tailbait.data.database.entities.DeviceLocationRecord> = emptyList(),
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

                // Time span - prefer record timestamps (immutable) over location timestamps (mutated by dedup)
                append("over ")
                if (deviceRecords.size >= 2) {
                    append(calculateTimeSpanStringFromRecords(deviceRecords))
                } else {
                    append(calculateTimeSpanString(locations))
                }
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
         * Calculate a human-readable time span string from device location records.
         *
         * Uses immutable record timestamps instead of location timestamps which
         * may be mutated by findOrCreateLocation dedup.
         *
         * @param records Device location records
         * @return Time span string (e.g., "the last 2 hours", "the last 3 days")
         */
        private fun calculateTimeSpanStringFromRecords(records: List<com.tailbait.data.database.entities.DeviceLocationRecord>): String {
            if (records.size < 2) return "unknown time"

            val oldest = records.minOf { it.timestamp }
            val newest = records.maxOf { it.timestamp }
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
        suspend fun runDetectionForDevice(deviceId: Long): DetectionResult? =
            withContext(Dispatchers.IO) {
                Timber.tag(TAG).d("Running detection for specific device: $deviceId")

                try {
                    val settings = settingsRepository.getSettings().first()
                    val whitelistedDeviceIds = whitelistRepository.getAllWhitelistedDeviceIds().first().toSet()

                    // Check if device is whitelisted
                    if (deviceId in whitelistedDeviceIds) {
                        Timber.tag(TAG).d("Device $deviceId is whitelisted")
                        return@withContext null
                    }

                    val candidate =
                        deviceRepository.getDeviceById(deviceId)
                            ?: run {
                                Timber.tag(TAG).w("Device $deviceId not found")
                                return@withContext null
                            }

                    val sinceTimestamp = System.currentTimeMillis() - (DETECTION_WINDOW_DAYS * 24 * 60 * 60 * 1000L)
                    val deviceRecords =
                        deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(deviceId, sinceTimestamp)
                            .filter { it.timestamp >= sinceTimestamp && it.deviceId !in whitelistedDeviceIds }
                    val rawLocations = getLocationsForDevice(deviceId, deviceRecords)
                    val locations = clusterLocations(rawLocations, settings.minDetectionDistanceMeters)
                    if (locations.size < rawLocations.size) {
                        Timber.tag(TAG).d(
                            "Device $deviceId: clustered ${rawLocations.size} locations → ${locations.size}",
                        )
                    }

                    if (locations.size < settings.alertThresholdCount) {
                        Timber.tag(TAG).d(
                            "Device $deviceId has insufficient locations: " +
                                "${locations.size} < ${settings.alertThresholdCount}",
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

                    val userPaths =
                        locationRepository.getUserPathSince(sinceTimestamp).filter { it.timestamp >= sinceTimestamp }
                    val pathLocationIds = userPaths.map { it.locationId }.toSet()
                    val userLocations =
                        locationRepository.getAllLocations().first()
                            .filter { it.timestamp >= sinceTimestamp || it.id in pathLocationIds }
                    val device = deviceForScoring(candidate, deviceRecords, sinceTimestamp)
                    val threatScore =
                        threatScoreCalculator.calculateEnhanced(
                            device = device,
                            locations = locations,
                            distances = distances,
                            deviceRecords = deviceRecords,
                            userLocations = userLocations,
                            userPaths = userPaths,
                        )

                    if (threatScore < MIN_THREAT_SCORE_FOR_ALERT) {
                        Timber.tag(TAG).d("Device $deviceId threat score too low: $threatScore")
                        return@withContext null
                    }

                    val breakdown =
                        threatScoreCalculator.computeBreakdown(
                            device,
                            locations,
                            distances,
                            deviceRecords,
                            userLocations,
                            userPaths,
                        )
                    DetectionResult(
                        device = device,
                        locations = locations,
                        threatScore = threatScore,
                        maxDistance = distances.maxOrNull() ?: 0.0,
                        avgDistance = if (distances.isNotEmpty()) distances.average() else 0.0,
                        detectionReason = buildDetectionReason(device, locations, threatScore, deviceRecords),
                        timeSpanMs =
                            if (deviceRecords.size >= 2) {
                                deviceRecords.maxOf { it.timestamp } - deviceRecords.minOf { it.timestamp }
                            } else {
                                0L
                            },
                        scoreBreakdown = breakdown,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error running detection for device $deviceId")
                    null
                }
            }
    }
