package com.tailbait.algorithm

import com.tailbait.data.database.entities.Location
import com.tailbait.util.DistanceCalculator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for detecting patterns in device location data.
 *
 * PatternMatcher provides utility functions for analyzing spatial and temporal
 * patterns in device detection data. It helps identify suspicious behaviors such as:
 * - Following patterns (device appears at many distant locations)
 * - Regular appearances (device seen repeatedly at similar times)
 * - Route correlation (device follows similar paths)
 * - Clustering (device appears in groups of locations)
 *
 * These pattern detection utilities support the main DetectionAlgorithm by
 * providing deeper analysis of device behavior beyond simple location counts.
 *
 * Thread Safety:
 * This class is stateless and thread-safe. It can be safely used as a singleton
 * and called from multiple threads concurrently.
 */
@Singleton
class PatternMatcher @Inject constructor() {

    companion object {
        // Clustering threshold - locations within 100m are considered the same cluster
        private const val CLUSTER_RADIUS_METERS = 100.0

        // Time window for temporal pattern detection (1 hour)
        private const val TEMPORAL_WINDOW_MS = 3600000L

        // Minimum locations for meaningful pattern analysis
        private const val MIN_LOCATIONS_FOR_PATTERN = 3
    }

    /**
     * Cluster locations into groups based on proximity.
     *
     * Locations within CLUSTER_RADIUS_METERS of each other are grouped into
     * the same cluster. This helps identify:
     * - How many truly distinct areas the device appears in
     * - Whether detections are spread out or concentrated
     *
     * Algorithm: Simple greedy clustering
     * 1. Start with first location as first cluster center
     * 2. For each subsequent location, check if it's within radius of any cluster
     * 3. If yes, add to that cluster; if no, create new cluster
     *
     * @param locations List of locations to cluster
     * @return List of location clusters
     */
    fun clusterLocations(locations: List<Location>): List<LocationCluster> {
        if (locations.isEmpty()) return emptyList()

        val clusters = mutableListOf<LocationCluster>()

        for (location in locations) {
            // Find existing cluster within radius
            val existingCluster = clusters.find { cluster ->
                DistanceCalculator.calculateDistance(
                    location,
                    cluster.centerLocation
                ) <= CLUSTER_RADIUS_METERS
            }

            if (existingCluster != null) {
                // Add to existing cluster
                existingCluster.locations.add(location)
            } else {
                // Create new cluster
                clusters.add(
                    LocationCluster(
                        centerLocation = location,
                        locations = mutableListOf(location)
                    )
                )
            }
        }

        return clusters
    }

    /**
     * Detect if locations show a following pattern.
     *
     * A following pattern is characterized by:
     * - Multiple distinct location clusters (not just one area)
     * - Significant distances between clusters (> 100m)
     * - Temporal progression (device moves from one cluster to another over time)
     *
     * @param locations List of locations to analyze
     * @param minClusters Minimum number of distinct clusters to consider following
     * @return True if following pattern detected
     */
    fun hasFollowingPattern(
        locations: List<Location>,
        minClusters: Int = 3
    ): Boolean {
        if (locations.size < MIN_LOCATIONS_FOR_PATTERN) return false

        val clusters = clusterLocations(locations)

        // Need at least minClusters distinct areas
        if (clusters.size < minClusters) return false

        // Check that clusters are temporally separated (not all at same time)
        val hasTemporalSeparation = clusters.any { cluster ->
            val otherClusters = clusters - cluster
            otherClusters.any { other ->
                val timeDiff = kotlin.math.abs(
                    cluster.getAverageTimestamp() - other.getAverageTimestamp()
                )
                timeDiff > TEMPORAL_WINDOW_MS
            }
        }

        return hasTemporalSeparation
    }

    /**
     * Calculate location diversity score.
     *
     * This metric measures how spread out the detections are geographically.
     * Higher scores indicate detections across a wide geographic area, which
     * is more suspicious than detections in a single area.
     *
     * Score calculation:
     * - Number of distinct clusters (weighted 60%)
     * - Average distance between clusters (weighted 40%)
     *
     * @param locations List of locations to analyze
     * @return Diversity score (0.0 = all in same place, 1.0 = maximum diversity)
     */
    fun calculateLocationDiversity(locations: List<Location>): Double {
        if (locations.size < 2) return 0.0

        val clusters = clusterLocations(locations)
        if (clusters.size < 2) return 0.0

        // Component 1: Number of clusters (normalized, max = 10 clusters)
        val clusterScore = kotlin.math.min(clusters.size / 10.0, 1.0) * 0.6

        // Component 2: Average distance between cluster centers
        val distances = mutableListOf<Double>()
        for (i in clusters.indices) {
            for (j in i + 1 until clusters.size) {
                val distance = DistanceCalculator.calculateDistance(
                    clusters[i].centerLocation,
                    clusters[j].centerLocation
                )
                distances.add(distance)
            }
        }

        val avgDistance = if (distances.isNotEmpty()) distances.average() else 0.0
        // Normalize by 5km (5000m = max diversity)
        val distanceScore = kotlin.math.min(avgDistance / 5000.0, 1.0) * 0.4

        return clusterScore + distanceScore
    }

    /**
     * Detect temporal patterns in device appearances.
     *
     * Analyzes whether the device appears at regular intervals, which could
     * indicate:
     * - Automated tracking (very regular intervals)
     * - Deliberate stalking (following a routine)
     * - Random coincidence (irregular intervals)
     *
     * @param locations List of locations sorted by timestamp
     * @return Temporal pattern analysis result
     */
    fun analyzeTemporalPattern(locations: List<Location>): TemporalPattern {
        if (locations.size < 2) {
            return TemporalPattern(
                isRegular = false,
                averageInterval = 0,
                variance = 0.0,
                pattern = "INSUFFICIENT_DATA"
            )
        }

        val sorted = locations.sortedBy { it.timestamp }
        val intervals = mutableListOf<Long>()

        for (i in 0 until sorted.size - 1) {
            intervals.add(sorted[i + 1].timestamp - sorted[i].timestamp)
        }

        val averageInterval = intervals.average()
        val variance = intervals.map { (it - averageInterval) * (it - averageInterval) }.average()
        val standardDeviation = kotlin.math.sqrt(variance)

        // Coefficient of variation (CV) = std dev / mean
        // Low CV (< 0.3) indicates regular pattern
        val coefficientOfVariation = standardDeviation / averageInterval
        val isRegular = coefficientOfVariation < 0.3

        val pattern = when {
            isRegular && averageInterval < TEMPORAL_WINDOW_MS -> "VERY_REGULAR" // < 1 hour
            isRegular -> "REGULAR"
            coefficientOfVariation < 0.6 -> "SOMEWHAT_REGULAR"
            else -> "IRREGULAR"
        }

        return TemporalPattern(
            isRegular = isRegular,
            averageInterval = averageInterval.toLong(),
            variance = variance,
            pattern = pattern
        )
    }

    /**
     * Check if two location lists show similar patterns.
     *
     * This can be used to correlate multiple devices or to compare against
     * known stalking patterns. Similarity is based on:
     * - Temporal overlap
     * - Spatial proximity
     * - Similar movement patterns
     *
     * @param locations1 First location list
     * @param locations2 Second location list
     * @return Similarity score (0.0 = completely different, 1.0 = identical)
     */
    fun calculatePatternSimilarity(
        locations1: List<Location>,
        locations2: List<Location>
    ): Double {
        if (locations1.isEmpty() || locations2.isEmpty()) return 0.0

        // Component 1: Temporal overlap (30%)
        val temporalScore = calculateTemporalOverlap(locations1, locations2) * 0.3

        // Component 2: Spatial proximity (40%)
        val spatialScore = calculateSpatialProximity(locations1, locations2) * 0.4

        // Component 3: Pattern similarity (30%)
        val pattern1 = analyzeTemporalPattern(locations1)
        val pattern2 = analyzeTemporalPattern(locations2)
        val patternScore = if (pattern1.pattern == pattern2.pattern) 0.3 else 0.0

        return temporalScore + spatialScore + patternScore
    }

    /**
     * Calculate temporal overlap between two location lists.
     */
    private fun calculateTemporalOverlap(
        locations1: List<Location>,
        locations2: List<Location>
    ): Double {
        val times1 = locations1.map { it.timestamp }
        val times2 = locations2.map { it.timestamp }

        val earliest = kotlin.math.max(times1.minOrNull() ?: 0, times2.minOrNull() ?: 0)
        val latest = kotlin.math.min(times1.maxOrNull() ?: 0, times2.maxOrNull() ?: 0)

        if (latest <= earliest) return 0.0

        val overlapDuration = latest - earliest
        val totalDuration = kotlin.math.max(
            (times1.maxOrNull() ?: 0) - (times1.minOrNull() ?: 0),
            (times2.maxOrNull() ?: 0) - (times2.minOrNull() ?: 0)
        )

        return if (totalDuration > 0) {
            overlapDuration.toDouble() / totalDuration.toDouble()
        } else {
            0.0
        }
    }

    /**
     * Calculate spatial proximity between two location lists.
     */
    private fun calculateSpatialProximity(
        locations1: List<Location>,
        locations2: List<Location>
    ): Double {
        var matchCount = 0
        var totalComparisons = 0

        for (loc1 in locations1) {
            for (loc2 in locations2) {
                totalComparisons++
                val distance = DistanceCalculator.calculateDistance(loc1, loc2)
                if (distance <= CLUSTER_RADIUS_METERS * 2) { // Within 200m
                    matchCount++
                }
            }
        }

        return if (totalComparisons > 0) {
            matchCount.toDouble() / totalComparisons.toDouble()
        } else {
            0.0
        }
    }
}

/**
 * Represents a cluster of nearby locations.
 *
 * @property centerLocation The representative center of the cluster
 * @property locations All locations in this cluster
 */
data class LocationCluster(
    val centerLocation: Location,
    val locations: MutableList<Location>
) {
    /**
     * Get the average timestamp of all locations in this cluster.
     */
    fun getAverageTimestamp(): Long {
        return if (locations.isNotEmpty()) {
            locations.map { it.timestamp }.average().toLong()
        } else {
            centerLocation.timestamp
        }
    }

    /**
     * Get the count of locations in this cluster.
     */
    fun getLocationCount(): Int = locations.size
}

/**
 * Represents temporal pattern analysis results.
 *
 * @property isRegular Whether the pattern is regular (low variance)
 * @property averageInterval Average time between sightings in milliseconds
 * @property variance Statistical variance of intervals
 * @property pattern Pattern classification (VERY_REGULAR, REGULAR, SOMEWHAT_REGULAR, IRREGULAR)
 */
data class TemporalPattern(
    val isRegular: Boolean,
    val averageInterval: Long,
    val variance: Double,
    val pattern: String
)
