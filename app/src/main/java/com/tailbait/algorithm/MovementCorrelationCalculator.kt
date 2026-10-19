package com.tailbait.algorithm

import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.UserPath
import com.tailbait.util.DistanceCalculator
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

/**
 * Movement Correlation Calculator
 *
 * Analyzes the correlation between a suspicious device's movement patterns and
 * the user's own movement patterns. This is the "smoking gun" for stalking detection:
 * a device that moves exactly when you move is far more suspicious than one that
 * just happens to share some locations with you.
 *
 * ## Scoring Factors (weights sum to 1.0)
 *
 * 1. **Movement Synchronization (40%)**: Do device appearances align with user location changes?
 *    - Measures temporal correlation between when user moves and when device is detected
 *    - High score: Device appears shortly after each user location change
 *    - Low score: Device appearances are random relative to user movement
 *
 * 2. **Route Overlap (30%)**: Does the device follow the same paths?
 *    - Analyzes sequential location matching (not just "visited same place")
 *    - High score: Device visits locations in same order as user
 *    - Low score: Device visits same locations but in different order (e.g., coworker)
 *
 * 3. **Dwell Time Matching (20%)**: Does device stay same duration at locations?
 *    - Compares how long device is detected at each location vs user dwell time
 *    - High score: Device stays similar duration as user at each location
 *    - Low score: Device dwell times don't match user patterns
 *
 * 4. **Time Pattern Analysis (10%)**: Regular appearance patterns
 *    - Detects if device appears at consistent times (e.g., every morning)
 *    - High score: Device appears at predictable times matching user routine
 *    - Low score: Device appearances are random in time
 *
 * ## Usage
 * ```kotlin
 * val calculator = MovementCorrelationCalculator()
 * val score = calculator.calculateCorrelation(
 *     deviceRecords = deviceLocationRecords,
 *     userPaths = userPathHistory,
 *     deviceLocations = deviceLocationHistory,
 *     userLocations = userLocationHistory
 * )
 * ```
 *
 * Thread Safety:
 * This class is stateless and thread-safe.
 */
@Singleton
class MovementCorrelationCalculator
    @Inject
    constructor() {
        companion object {
            private const val TAG = "MovementCorrelation"

            // Scoring weights (must sum to 1.0)
            private const val WEIGHT_MOVEMENT_SYNC = 0.40
            private const val WEIGHT_ROUTE_OVERLAP = 0.30
            private const val WEIGHT_DWELL_TIME = 0.20
            private const val WEIGHT_TIME_PATTERN = 0.10

            // Thresholds
            private const val SYNC_WINDOW_MS = 300_000L // 5 minutes - device should appear within this window of user movement
            private const val MIN_RECORDS_FOR_ANALYSIS = 3 // Minimum records needed for meaningful analysis

            // Time pattern thresholds
            private const val HOUR_MS = 3_600_000L
            private const val TIME_PATTERN_WINDOW_HOURS = 2 // Consider same time of day if within 2 hours
        }

        /**
         * Detailed breakdown of the movement correlation score.
         */
        data class CorrelationBreakdown(
            val movementSyncScore: Double,
            val routeOverlapScore: Double,
            val dwellTimeScore: Double,
            val timePatternScore: Double,
            val totalScore: Double,
            val analysisDetails: String,
        )

        /**
         * Calculate the movement correlation score for a device.
         *
         * @param deviceRecords List of device-location records (when device was detected at each location)
         * @param userPaths User movement history (UserPath entity)
         * @param deviceLocations List of locations where device was detected (for position data)
         * @param userLocations Place coordinates referenced by the user's movement history
         * @return Correlation score between 0.0 (no correlation) and 1.0 (perfect correlation)
         */
        fun calculateCorrelation(
            deviceRecords: List<DeviceLocationRecord>,
            userPaths: List<UserPath>,
            deviceLocations: List<Location>,
            userLocations: List<Location>,
        ): Double {
            return calculateWithBreakdown(deviceRecords, userPaths, deviceLocations, userLocations).totalScore
        }

        /**
         * Calculate correlation with detailed breakdown.
         */
        fun calculateWithBreakdown(
            deviceRecords: List<DeviceLocationRecord>,
            userPaths: List<UserPath>,
            deviceLocations: List<Location>,
            userLocations: List<Location>,
        ): CorrelationBreakdown {
            // Need minimum data for meaningful analysis
            if (deviceRecords.size < MIN_RECORDS_FOR_ANALYSIS || userPaths.size < MIN_RECORDS_FOR_ANALYSIS) {
                Timber.tag(
                    TAG,
                ).d(
                    "Insufficient data for correlation analysis: ${deviceRecords.size} device records, ${userPaths.size} user paths",
                )
                return CorrelationBreakdown(
                    movementSyncScore = 0.0,
                    routeOverlapScore = 0.0,
                    dwellTimeScore = 0.0,
                    timePatternScore = 0.0,
                    totalScore = 0.0,
                    analysisDetails = "Insufficient data for analysis",
                )
            }

            // Sort by timestamp (UserPath is already time-ordered usually, but ensure it)
            val sortedDeviceRecords = deviceRecords.sortedBy { it.timestamp }
            val sortedUserPaths = userPaths.sortedBy { it.timestamp }
            val locationsById = deviceLocations.associateByTo(mutableMapOf()) { it.id }
            userLocations.associateByTo(locationsById) { it.id }
            val userMovements = BooleanArray(sortedUserPaths.size)
            for (index in 1 until sortedUserPaths.size) {
                val previous = sortedUserPaths[index - 1]
                val current = sortedUserPaths[index]
                userMovements[index] =
                    hasProvenMovement(
                        locationsById[previous.locationId],
                        locationsById[current.locationId],
                        previous.accuracy,
                        current.accuracy,
                    )
            }

            // 1. Movement Synchronization (40%)
            // Do we see the device moving when the user moves?
            val syncScore = calculateMovementSynchronization(sortedDeviceRecords, sortedUserPaths, userMovements)

            // 2. Route Overlap (30%)
            // Is the sequence of locations the same?
            val routeScore = calculateRouteOverlap(sortedDeviceRecords, sortedUserPaths, userMovements, locationsById)

            // 3. Dwell Time Match (20%)
            // Does the device stay/leave when the user does?
            val dwellScore = calculateDwellTimeMatch(sortedDeviceRecords, sortedUserPaths)

            // 4. Time Pattern (10%)
            // Is the device seen at consistent times?
            val timeScore = calculateTimePattern(sortedDeviceRecords)

            val totalScore =
                (syncScore * WEIGHT_MOVEMENT_SYNC) +
                    (routeScore * WEIGHT_ROUTE_OVERLAP) +
                    (dwellScore * WEIGHT_DWELL_TIME) +
                    (timeScore * WEIGHT_TIME_PATTERN)

            Timber.tag(TAG).v(
                "Correlation scores - Sync: %.2f, Route: %.2f, Dwell: %.2f, Time: %.2f -> Total: %.2f",
                syncScore,
                routeScore,
                dwellScore,
                timeScore,
                totalScore,
            )

            val details =
                buildString {
                    append(
                        "MovementSync: ${(syncScore * 100).toInt()}% (${(syncScore * WEIGHT_MOVEMENT_SYNC * 100).toInt()}), ",
                    )
                    append(
                        "RouteOverlap: ${(routeScore * 100).toInt()}% (${(routeScore * WEIGHT_ROUTE_OVERLAP * 100).toInt()}), ",
                    )
                    append(
                        "DwellMatch: ${(dwellScore * 100).toInt()}% (${(dwellScore * WEIGHT_DWELL_TIME * 100).toInt()}), ",
                    )
                    append(
                        "TimePattern: ${(timeScore * 100).toInt()}% (${(timeScore * WEIGHT_TIME_PATTERN * 100).toInt()})",
                    )
                }

            Timber.tag(TAG).d("Correlation analysis: $details, Total: ${(totalScore * 100).toInt()}%")

            return CorrelationBreakdown(
                movementSyncScore = syncScore,
                routeOverlapScore = routeScore,
                dwellTimeScore = dwellScore,
                timePatternScore = timeScore,
                totalScore = min(totalScore, 1.0),
                analysisDetails = details,
            )
        }

        /**
         * Analyze if device movements synchronize with user movements.
         *
         * A location ID change is movement evidence only when the fixes' accuracy
         * regions do not overlap.
         *
         * @param deviceRecords Device detection history
         * @param userPaths User movement history
         */
        private fun calculateMovementSynchronization(
            deviceRecords: List<DeviceLocationRecord>,
            userPaths: List<UserPath>,
            userMovements: BooleanArray,
        ): Double {
            var movementCount = 0
            var correlatedMovements = 0

            for (index in 1 until userPaths.size) {
                if (userPaths[index].locationId == userPaths[index - 1].locationId) continue
                // Keep uncertain transitions in the denominator so losing accuracy cannot raise confidence.
                movementCount++
                if (!userMovements[index]) continue

                val moveTime = userPaths[index].timestamp
                if (deviceRecords.any { abs(it.timestamp - moveTime) < SYNC_WINDOW_MS }) {
                    correlatedMovements++
                }
            }

            val score = if (movementCount > 0) correlatedMovements.toDouble() / movementCount else 0.0
            Timber.tag(TAG).v("Movement sync: $correlatedMovements/$movementCount movements matched")
            return score
        }

        /**
         * Calculate overlap between device route and user route.
         *
         * Uses Longest Common Subsequence (LCS) on Location IDs supported by
         * spatially separated fixes, rather than GPS drift or missing coordinates.
         *
         * @param deviceRecords Device detection history
         * @param userPaths User movement history
         */
        private fun calculateRouteOverlap(
            deviceRecords: List<DeviceLocationRecord>,
            userPaths: List<UserPath>,
            userMovements: BooleanArray,
            locationsById: Map<Long, Location>,
        ): Double {
            if (deviceRecords.isEmpty() || userPaths.isEmpty()) return 0.0

            // Only endpoints of proven movements contribute to the spatial route.
            val userRoute =
                userPaths.mapIndexedNotNull { index, path ->
                    if (userMovements[index] || (index < userPaths.lastIndex && userMovements[index + 1])) {
                        path.locationId
                    } else {
                        null
                    }
                }.distinctUntilChanged()

            // Extract sequence of Location IDs where Device was seen
            val deviceRoute =
                deviceRecords.sortedBy { it.timestamp }
                    .map { it.locationId }
                    .distinctUntilChanged()
                    .toList()

            val spatialDeviceRoute =
                deviceRoute.filterIndexed { index, locationId ->
                    val location = locationsById[locationId]
                    (index > 0 && hasProvenMovement(locationsById[deviceRoute[index - 1]], location)) ||
                        (index < deviceRoute.lastIndex && hasProvenMovement(location, locationsById[deviceRoute[index + 1]]))
                }

            if (userRoute.size < 2 || spatialDeviceRoute.size < 2) return 0.0

            // Calculate LCS of Location IDs
            val lcsLength = calculateLCS(userRoute, spatialDeviceRoute)

            // Keep the full device route as the denominator when accuracy removes evidence.
            val score = lcsLength.toDouble() / deviceRoute.size
            Timber.tag(TAG).v("Route overlap: LCS=$lcsLength/$deviceRoute.size")
            return score
        }

        private fun hasProvenMovement(
            first: Location?,
            second: Location?,
            firstAccuracy: Float = first?.accuracy ?: 0f,
            secondAccuracy: Float = second?.accuracy ?: 0f,
        ): Boolean {
            if (first == null || second == null || first.id == second.id) return false
            if (firstAccuracy <= 0f || secondAccuracy <= 0f || first.accuracy <= 0f || second.accuracy <= 0f) return false

            // Breadcrumbs reuse place coordinates, so neither source may understate uncertainty.
            return DistanceCalculator.minimumSeparationMeters(
                DistanceCalculator.calculateDistance(first, second),
                maxOf(firstAccuracy, first.accuracy),
                maxOf(secondAccuracy, second.accuracy),
            ) > 0.0
        }

        /**
         * Factor 3: Dwell Time Matching (20%)
         *
         * Analyzes whether device stays similar duration at locations as user.
         *
         * @param deviceRecords Device detection records
         * @param userPaths User movement history
         */
        private fun calculateDwellTimeMatch(
            deviceRecords: List<DeviceLocationRecord>,
            userPaths: List<UserPath>,
        ): Double {
            // Group by location ID
            val deviceDwells = deviceRecords.groupBy { it.locationId }
            val userDwells = userPaths.groupBy { it.locationId }

            var matchCount = 0
            var sharedLocations = 0

            for ((locId, dRecords) in deviceDwells) {
                val uPaths = userDwells[locId] ?: continue
                sharedLocations++

                // Calculate dwell duration for this location
                val deviceDuration = calculateDuration(dRecords.map { it.timestamp })
                val userDuration = calculateDuration(uPaths.map { it.timestamp })

                // Similar duration? (within 50% or 10 mins)
                val diff = abs(deviceDuration - userDuration)
                if (diff < (userDuration * 0.5) || diff < 10 * 60 * 1000L) {
                    matchCount++
                }
            }

            return if (sharedLocations > 0) matchCount.toDouble() / sharedLocations else 0.0
        }

        private fun calculateDuration(timestamps: List<Long>): Long {
            if (timestamps.isEmpty()) return 0
            val min = timestamps.minOrNull() ?: 0
            val max = timestamps.maxOrNull() ?: 0
            return max - min
        }

        /**
         * Factor 4: Time Pattern Analysis (10%)
         *
         * Detects regular temporal patterns in device appearances.
         * A stalker may consistently appear at the same time each day.
         *
         * Algorithm:
         * 1. Extract hour-of-day for each detection
         * 2. Check for clustering of detection times
         * 3. Score based on temporal regularity
         */
        private fun calculateTimePattern(deviceRecords: List<DeviceLocationRecord>): Double {
            if (deviceRecords.size < 3) return 0.0

            // Extract hour of day for each detection
            val hoursOfDay =
                deviceRecords.map { record ->
                    val hourOfDay = (record.timestamp % (24 * HOUR_MS)) / HOUR_MS
                    hourOfDay.toInt()
                }

            // Count occurrences in each hour bucket
            val hourCounts = IntArray(24)
            hoursOfDay.forEach { hour ->
                hourCounts[hour]++
            }

            // Find peak hour(s) and calculate concentration
            val maxCount = hourCounts.maxOrNull() ?: 0
            val peakHours = hourCounts.count { it >= maxCount * 0.7 } // Hours with 70%+ of max

            // Score based on concentration
            // If all detections happen in a narrow time window, that's suspicious
            val concentration = maxCount.toDouble() / deviceRecords.size
            val windowScore =
                if (peakHours <= TIME_PATTERN_WINDOW_HOURS) {
                    concentration
                } else {
                    concentration * 0.5 // Reduce score for spread-out patterns
                }

            Timber.tag(
                TAG,
            ).v(
                "Time pattern: peak concentration=${(concentration * 100).toInt()}%, peak hours=$peakHours, score=${(windowScore * 100).toInt()}%",
            )
            return min(windowScore, 1.0)
        }

        // ============================================================================
        // Helper Functions
        // ============================================================================

        /**
         * Calculate Longest Common Subsequence length for two sequences.
         */
        private fun calculateLCS(
            seq1: List<Long>,
            seq2: List<Long>,
        ): Int {
            val m = seq1.size
            val n = seq2.size
            val dp = Array(m + 1) { IntArray(n + 1) }

            for (i in 1..m) {
                for (j in 1..n) {
                    if (seq1[i - 1] == seq2[j - 1]) {
                        dp[i][j] = dp[i - 1][j - 1] + 1
                    } else {
                        dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
                    }
                }
            }

            return dp[m][n]
        }

        /**
         * Helper extension to collapse consecutive duplicates.
         */
        private fun <T> Iterable<T>.distinctUntilChanged(): List<T> {
            val result = mutableListOf<T>()
            var last: T? = null
            var first = true
            for (item in this) {
                if (first || item != last) {
                    result.add(item)
                    last = item
                    first = false
                }
            }
            return result
        }
    }
