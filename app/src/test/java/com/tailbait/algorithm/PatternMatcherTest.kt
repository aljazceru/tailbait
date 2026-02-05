package com.tailbait.algorithm

import com.tailbait.data.database.entities.Location
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PatternMatcher.
 *
 * Tests pattern detection, clustering, and temporal analysis functionality.
 */
class PatternMatcherTest {

    private lateinit var patternMatcher: PatternMatcher

    @Before
    fun setup() {
        patternMatcher = PatternMatcher()
    }

    // ==================== Location Clustering Tests ====================

    @Test
    fun `clusterLocations returns empty list for empty input`() {
        val result = patternMatcher.clusterLocations(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `clusterLocations creates single cluster for nearby locations`() {
        // Three locations within 100m of each other
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L), // New York
            createLocation(2L, 40.7129, -74.0061, 2000L), // ~15m away
            createLocation(3L, 40.7127, -74.0059, 3000L)  // ~20m away
        )

        val clusters = patternMatcher.clusterLocations(locations)

        assertEquals(1, clusters.size)
        assertEquals(3, clusters[0].getLocationCount())
    }

    @Test
    fun `clusterLocations creates multiple clusters for distant locations`() {
        // Three locations far apart (> 100m)
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L), // New York
            createLocation(2L, 40.7589, -73.9851, 2000L), // Times Square (~5km)
            createLocation(3L, 40.7489, -73.9680, 3000L)  // Grand Central (~3km)
        )

        val clusters = patternMatcher.clusterLocations(locations)

        assertEquals(3, clusters.size)
        assertTrue(clusters.all { it.getLocationCount() == 1 })
    }

    @Test
    fun `clusterLocations handles mixed nearby and distant locations`() {
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L), // Cluster 1
            createLocation(2L, 40.7129, -74.0061, 1500L), // Cluster 1
            createLocation(3L, 40.7589, -73.9851, 2000L), // Cluster 2 (far)
            createLocation(4L, 40.7590, -73.9852, 2500L), // Cluster 2
            createLocation(5L, 40.7489, -73.9680, 3000L)  // Cluster 3 (far)
        )

        val clusters = patternMatcher.clusterLocations(locations)

        assertEquals(3, clusters.size)
        // First cluster should have 2 locations
        assertTrue(clusters.any { it.getLocationCount() == 2 })
    }

    // ==================== Following Pattern Tests ====================

    @Test
    fun `hasFollowingPattern returns false for insufficient locations`() {
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L),
            createLocation(2L, 40.7589, -73.9851, 2000L)
        )

        val result = patternMatcher.hasFollowingPattern(locations)

        assertFalse(result)
    }

    @Test
    fun `hasFollowingPattern returns false when locations in same cluster`() {
        // All locations within 100m (single cluster)
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L),
            createLocation(2L, 40.7129, -74.0061, 2000L),
            createLocation(3L, 40.7127, -74.0059, 3000L),
            createLocation(4L, 40.7130, -74.0062, 4000L)
        )

        val result = patternMatcher.hasFollowingPattern(locations)

        assertFalse(result)
    }

    @Test
    fun `hasFollowingPattern returns true for distinct temporal clusters`() {
        // Three distinct location clusters separated by time (> 1 hour)
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L),        // Cluster 1 - morning
            createLocation(2L, 40.7589, -73.9851, 4000000L),     // Cluster 2 - afternoon
            createLocation(3L, 40.7489, -73.9680, 8000000L)      // Cluster 3 - evening
        )

        val result = patternMatcher.hasFollowingPattern(locations)

        assertTrue(result)
    }

    @Test
    fun `hasFollowingPattern returns false when clusters not temporally separated`() {
        // Three clusters but all at similar times
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L),
            createLocation(2L, 40.7589, -73.9851, 2000L),
            createLocation(3L, 40.7489, -73.9680, 3000L)
        )

        val result = patternMatcher.hasFollowingPattern(locations, minClusters = 3)

        // Even though there are 3 clusters, they're all within 1 hour
        assertFalse(result)
    }

    // ==================== Location Diversity Tests ====================

    @Test
    fun `calculateLocationDiversity returns zero for single location`() {
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L)
        )

        val diversity = patternMatcher.calculateLocationDiversity(locations)

        assertEquals(0.0, diversity, 0.01)
    }

    @Test
    fun `calculateLocationDiversity returns zero for same location`() {
        // Two locations in same spot
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L),
            createLocation(2L, 40.7128, -74.0060, 2000L)
        )

        val diversity = patternMatcher.calculateLocationDiversity(locations)

        assertEquals(0.0, diversity, 0.01)
    }

    @Test
    fun `calculateLocationDiversity returns higher score for distant locations`() {
        // Locations far apart show high diversity
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L), // New York
            createLocation(2L, 40.7589, -73.9851, 2000L), // ~5km
            createLocation(3L, 40.7489, -73.9680, 3000L), // ~3km
            createLocation(4L, 40.7300, -73.9950, 4000L)  // ~2km
        )

        val diversity = patternMatcher.calculateLocationDiversity(locations)

        assertTrue("Expected diversity > 0.3, got $diversity", diversity > 0.3)
    }

    @Test
    fun `calculateLocationDiversity is higher for more clusters`() {
        // More distinct clusters = higher diversity
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L),
            createLocation(2L, 40.7589, -73.9851, 2000L),
            createLocation(3L, 40.7489, -73.9680, 3000L),
            createLocation(4L, 40.7389, -73.9500, 4000L),
            createLocation(5L, 40.7289, -73.9350, 5000L)
        )

        val diversity = patternMatcher.calculateLocationDiversity(locations)

        // With 5 distinct clusters far apart, should have high diversity
        assertTrue("Expected diversity > 0.4, got $diversity", diversity > 0.4)
    }

    // ==================== Temporal Pattern Tests ====================

    @Test
    fun `analyzeTemporalPattern returns INSUFFICIENT_DATA for single location`() {
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L)
        )

        val pattern = patternMatcher.analyzeTemporalPattern(locations)

        assertFalse(pattern.isRegular)
        assertEquals("INSUFFICIENT_DATA", pattern.pattern)
    }

    @Test
    fun `analyzeTemporalPattern detects VERY_REGULAR pattern`() {
        // Locations at exactly 30-minute intervals
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 0L),
            createLocation(2L, 40.7129, -74.0061, 1800000L), // +30 min
            createLocation(3L, 40.7130, -74.0062, 3600000L), // +30 min
            createLocation(4L, 40.7131, -74.0063, 5400000L)  // +30 min
        )

        val pattern = patternMatcher.analyzeTemporalPattern(locations)

        assertTrue(pattern.isRegular)
        assertEquals("VERY_REGULAR", pattern.pattern)
        assertEquals(1800000L, pattern.averageInterval)
    }

    @Test
    fun `analyzeTemporalPattern detects REGULAR pattern for consistent intervals`() {
        // Locations at roughly 2-hour intervals
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 0L),
            createLocation(2L, 40.7129, -74.0061, 7200000L),  // +2 hours
            createLocation(3L, 40.7130, -74.0062, 14400000L), // +2 hours
            createLocation(4L, 40.7131, -74.0063, 21600000L)  // +2 hours
        )

        val pattern = patternMatcher.analyzeTemporalPattern(locations)

        assertTrue(pattern.isRegular)
        assertEquals("REGULAR", pattern.pattern)
    }

    @Test
    fun `analyzeTemporalPattern detects IRREGULAR pattern for random intervals`() {
        // Locations at very random intervals
        val locations = listOf(
            createLocation(1L, 40.7128, -74.0060, 0L),
            createLocation(2L, 40.7129, -74.0061, 500000L),     // +8 min
            createLocation(3L, 40.7130, -74.0062, 10000000L),   // +2.6 hours
            createLocation(4L, 40.7131, -74.0063, 12000000L),   // +33 min
            createLocation(5L, 40.7132, -74.0064, 30000000L)    // +5 hours
        )

        val pattern = patternMatcher.analyzeTemporalPattern(locations)

        assertFalse(pattern.isRegular)
        assertEquals("IRREGULAR", pattern.pattern)
    }

    // ==================== Pattern Similarity Tests ====================

    @Test
    fun `calculatePatternSimilarity returns zero for empty lists`() {
        val result = patternMatcher.calculatePatternSimilarity(emptyList(), emptyList())
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `calculatePatternSimilarity returns high score for identical patterns`() {
        val locations1 = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L),
            createLocation(2L, 40.7589, -73.9851, 2000L),
            createLocation(3L, 40.7489, -73.9680, 3000L)
        )

        val locations2 = listOf(
            createLocation(4L, 40.7128, -74.0060, 1100L), // Same places
            createLocation(5L, 40.7589, -73.9851, 2100L),
            createLocation(6L, 40.7489, -73.9680, 3100L)
        )

        val similarity = patternMatcher.calculatePatternSimilarity(locations1, locations2)

        assertTrue("Expected similarity > 0.5, got $similarity", similarity > 0.5)
    }

    @Test
    fun `calculatePatternSimilarity returns low score for completely different patterns`() {
        val locations1 = listOf(
            createLocation(1L, 40.7128, -74.0060, 1000L),
            createLocation(2L, 40.7129, -74.0061, 2000L)
        )

        val locations2 = listOf(
            createLocation(3L, 34.0522, -118.2437, 10000000L), // Los Angeles, much later
            createLocation(4L, 34.0523, -118.2438, 20000000L)
        )

        val similarity = patternMatcher.calculatePatternSimilarity(locations1, locations2)

        assertTrue("Expected similarity < 0.3, got $similarity", similarity < 0.3)
    }

    // ==================== LocationCluster Tests ====================

    @Test
    fun `LocationCluster getAverageTimestamp returns center timestamp for single location`() {
        val location = createLocation(1L, 40.7128, -74.0060, 5000L)
        val cluster = LocationCluster(location, mutableListOf(location))

        assertEquals(5000L, cluster.getAverageTimestamp())
    }

    @Test
    fun `LocationCluster getAverageTimestamp calculates average correctly`() {
        val centerLocation = createLocation(1L, 40.7128, -74.0060, 1000L)
        val locations = mutableListOf(
            createLocation(1L, 40.7128, -74.0060, 1000L),
            createLocation(2L, 40.7129, -74.0061, 2000L),
            createLocation(3L, 40.7130, -74.0062, 3000L)
        )

        val cluster = LocationCluster(centerLocation, locations)

        assertEquals(2000L, cluster.getAverageTimestamp()) // (1000+2000+3000)/3
    }

    @Test
    fun `LocationCluster getLocationCount returns correct count`() {
        val centerLocation = createLocation(1L, 40.7128, -74.0060, 1000L)
        val locations = mutableListOf(
            createLocation(1L, 40.7128, -74.0060, 1000L),
            createLocation(2L, 40.7129, -74.0061, 2000L),
            createLocation(3L, 40.7130, -74.0062, 3000L)
        )

        val cluster = LocationCluster(centerLocation, locations)

        assertEquals(3, cluster.getLocationCount())
    }

    // ==================== TemporalPattern Tests ====================

    @Test
    fun `TemporalPattern stores all properties correctly`() {
        val pattern = TemporalPattern(
            isRegular = true,
            averageInterval = 3600000L,
            variance = 1000.0,
            pattern = "VERY_REGULAR"
        )

        assertTrue(pattern.isRegular)
        assertEquals(3600000L, pattern.averageInterval)
        assertEquals(1000.0, pattern.variance, 0.01)
        assertEquals("VERY_REGULAR", pattern.pattern)
    }

    // ==================== Helper Methods ====================

    /**
     * Helper function to create test locations.
     */
    private fun createLocation(
        id: Long,
        latitude: Double,
        longitude: Double,
        timestamp: Long
    ): Location {
        return Location(
            id = id,
            latitude = latitude,
            longitude = longitude,
            accuracy = 10f,
            timestamp = timestamp,
            provider = "GPS"
        )
    }
}
