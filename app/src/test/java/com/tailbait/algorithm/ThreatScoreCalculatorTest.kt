package com.tailbait.algorithm

import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive unit tests for ThreatScoreCalculator.
 *
 * These tests verify that the threat scoring algorithm correctly calculates
 * scores based on various device detection patterns. The tests cover:
 * - Individual scoring factors (location count, distance, time, consistency, device type)
 * - Combined scoring scenarios
 * - Edge cases and boundary conditions
 * - Score normalization and capping
 * - Detailed breakdown calculations
 *
 * Test Methodology:
 * - Use realistic test data based on actual stalking patterns
 * - Verify scores are within expected ranges (0.0 - 1.0)
 * - Test all five scoring factors independently
 * - Test factor interactions and total score calculation
 * - Verify threat score breakdown matches total score
 */
class ThreatScoreCalculatorTest {

    private lateinit var calculator: ThreatScoreCalculator
    private lateinit var movementCorrelationCalculator: MovementCorrelationCalculator

    @Before
    fun setup() {
        movementCorrelationCalculator = mockk()
        calculator = ThreatScoreCalculator(movementCorrelationCalculator)
    }

    // ==================== Location Count Factor Tests (0.0 - 0.3) ====================

    @Test
    fun `calculate - 3 locations - should give low location score`() {
        val device = createTestDevice("PHONE")
        val locations = createTestLocations(3, intervalHours = 1)
        val distances = listOf(500.0, 1000.0, 1500.0)

        val score = calculator.calculate(device, locations, distances)

        // 3 locations = 3/10 * 0.3 = 0.09 from location factor
        // Score should be relatively low
        assertTrue("Score should be positive", score > 0.0)
        assertTrue("Score should be less than 0.5 for only 3 locations", score < 0.5)
    }

    @Test
    fun `calculate - 5 locations - should give medium location score`() {
        val device = createTestDevice("PHONE")
        val locations = createTestLocations(5, intervalHours = 2)
        val distances = createDistancesFromLocations(locations)

        val score = calculator.calculate(device, locations, distances)

        // 5 locations = 5/10 * 0.3 = 0.15 from location factor
        // Should be noticeable increase from 3 locations
        assertTrue("Score should be between 0.2 and 0.7", score in 0.2..0.7)
    }

    @Test
    fun `calculate - 10+ locations - should give maximum location score`() {
        val device = createTestDevice("PHONE")
        val locations = createTestLocations(12, intervalHours = 1)
        val distances = createDistancesFromLocations(locations)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        // 12 locations = max(12/10, 1.0) * 0.3 = 0.3 (capped)
        assertEquals("Location score should be capped at 0.3", 0.3, breakdown.locationCountScore, 0.01)
    }

    // ==================== Distance Factor Tests (0.0 - 0.25) ====================

    @Test
    fun `calculate - short distances - should give low distance score`() {
        val device = createTestDevice("PHONE")
        val locations = createTestLocations(3, intervalHours = 1, baseLatitude = 40.0, baseLongitude = -74.0)
        // All locations within 100m (close proximity)
        val distances = listOf(50.0, 75.0, 100.0)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        // Max distance 100m = 100/10000 * 0.25 = 0.0025
        assertTrue("Distance score should be very low for short distances", breakdown.distanceScore < 0.01)
    }

    @Test
    fun `calculate - medium distances - should give medium distance score`() {
        val device = createTestDevice("PHONE")
        val locations = createTestLocations(3, intervalHours = 1)
        // Max distance ~2km
        val distances = listOf(500.0, 1500.0, 2000.0)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        // Max distance 2000m = 2000/10000 * 0.25 = 0.05
        assertTrue("Distance score should be around 0.05", breakdown.distanceScore in 0.04..0.06)
    }

    @Test
    fun `calculate - long distances - should give high distance score`() {
        val device = createTestDevice("PHONE")
        val locations = createTestLocations(3, intervalHours = 1)
        // Max distance 15km (beyond normalization threshold)
        val distances = listOf(5000.0, 10000.0, 15000.0)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        // Max distance 15000m = max(15000/10000, 1.0) * 0.25 = 0.25 (capped)
        assertEquals("Distance score should be capped at 0.25", 0.25, breakdown.distanceScore, 0.01)
    }

    // ==================== Time Correlation Factor Tests (0.0 - 0.2) ====================

    @Test
    fun `calculate - less than 1 hour span - should give low time score`() {
        val device = createTestDevice("PHONE")
        val baseTime = System.currentTimeMillis()
        val locations = listOf(
            createLocation(40.0, -74.0, baseTime),
            createLocation(40.01, -74.01, baseTime + 30 * 60 * 1000), // +30 min
            createLocation(40.02, -74.02, baseTime + 45 * 60 * 1000)  // +45 min
        )
        val distances = createDistancesFromLocations(locations)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        // < 1 hour = 0.05 * 0.2 / 0.2 = 0.05
        assertTrue("Time score should be around 0.05 for < 1 hour", breakdown.timeCorrelationScore < 0.06)
    }

    @Test
    fun `calculate - 1 to 24 hours span - should give medium time score`() {
        val device = createTestDevice("PHONE")
        val baseTime = System.currentTimeMillis()
        val locations = listOf(
            createLocation(40.0, -74.0, baseTime),
            createLocation(40.01, -74.01, baseTime + 6 * 60 * 60 * 1000), // +6 hours
            createLocation(40.02, -74.02, baseTime + 12 * 60 * 60 * 1000) // +12 hours
        )
        val distances = createDistancesFromLocations(locations)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        // 1-24 hours = 0.15 * 0.2 / 0.2 = 0.15
        assertTrue("Time score should be around 0.15 for 1-24 hours", breakdown.timeCorrelationScore in 0.14..0.16)
    }

    @Test
    fun `calculate - more than 1 day span - should give high time score`() {
        val device = createTestDevice("PHONE")
        val baseTime = System.currentTimeMillis()
        val locations = listOf(
            createLocation(40.0, -74.0, baseTime),
            createLocation(40.01, -74.01, baseTime + 1 * 24 * 60 * 60 * 1000), // +1 day
            createLocation(40.02, -74.02, baseTime + 3 * 24 * 60 * 60 * 1000)  // +3 days
        )
        val distances = createDistancesFromLocations(locations)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        // > 1 day = 0.2 (maximum)
        assertEquals("Time score should be 0.2 for > 1 day", 0.2, breakdown.timeCorrelationScore, 0.01)
    }

    // ==================== Consistency Factor Tests (0.0 - 0.15) ====================

    @Test
    fun `calculate - very regular appearances - should give high consistency score`() {
        val device = createTestDevice("PHONE")
        val baseTime = System.currentTimeMillis()
        // Detections every 30 minutes (very regular)
        val locations = listOf(
            createLocation(40.0, -74.0, baseTime),
            createLocation(40.01, -74.01, baseTime + 30 * 60 * 1000),
            createLocation(40.02, -74.02, baseTime + 60 * 60 * 1000),
            createLocation(40.03, -74.03, baseTime + 90 * 60 * 1000)
        )
        val distances = createDistancesFromLocations(locations)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        // Avg interval < 1 hour = 0.15 (maximum consistency)
        assertEquals("Consistency score should be 0.15 for very regular", 0.15, breakdown.consistencyScore, 0.01)
    }

    @Test
    fun `calculate - irregular appearances - should give low consistency score`() {
        val device = createTestDevice("PHONE")
        val baseTime = System.currentTimeMillis()
        // Irregular intervals: 30min, 6 hours, 2 days
        val locations = listOf(
            createLocation(40.0, -74.0, baseTime),
            createLocation(40.01, -74.01, baseTime + 30 * 60 * 1000),
            createLocation(40.02, -74.02, baseTime + 6 * 60 * 60 * 1000 + 30 * 60 * 1000),
            createLocation(40.03, -74.03, baseTime + 2 * 24 * 60 * 60 * 1000 + 6 * 60 * 60 * 1000)
        )
        val distances = createDistancesFromLocations(locations)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        // Very irregular = low consistency score
        assertTrue("Consistency score should be low for irregular", breakdown.consistencyScore < 0.1)
    }

    // ==================== Device Type Factor Tests (0.0 - 0.1) ====================

    @Test
    fun `calculate - phone device type - should give maximum device type score`() {
        val device = createTestDevice("PHONE")
        val locations = createTestLocations(3, intervalHours = 1)
        val distances = listOf(1000.0)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        assertEquals("Phone should give 0.1 device score", 0.1, breakdown.deviceTypeScore, 0.001)
    }

    @Test
    fun `calculate - tablet device type - should give maximum device type score`() {
        val device = createTestDevice("TABLET")
        val locations = createTestLocations(3, intervalHours = 1)
        val distances = listOf(1000.0)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        assertEquals("Tablet should give 0.1 device score", 0.1, breakdown.deviceTypeScore, 0.001)
    }

    @Test
    fun `calculate - watch device type - should give medium device type score`() {
        val device = createTestDevice("WATCH")
        val locations = createTestLocations(3, intervalHours = 1)
        val distances = listOf(1000.0)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        assertEquals("Watch should give 0.08 device score", 0.08, breakdown.deviceTypeScore, 0.001)
    }

    @Test
    fun `calculate - tracker device type - should give medium device type score`() {
        val device = createTestDevice("TRACKER")
        val locations = createTestLocations(3, intervalHours = 1)
        val distances = listOf(1000.0)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        assertEquals("Tracker should give 0.08 device score", 0.08, breakdown.deviceTypeScore, 0.001)
    }

    @Test
    fun `calculate - headphones device type - should give low device type score`() {
        val device = createTestDevice("HEADPHONES")
        val locations = createTestLocations(3, intervalHours = 1)
        val distances = listOf(1000.0)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        assertEquals("Headphones should give 0.05 device score", 0.05, breakdown.deviceTypeScore, 0.001)
    }

    @Test
    fun `calculate - unknown device type - should give default device type score`() {
        val device = createTestDevice(null)
        val locations = createTestLocations(3, intervalHours = 1)
        val distances = listOf(1000.0)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        assertEquals("Unknown should give 0.07 device score", 0.07, breakdown.deviceTypeScore, 0.001)
    }

    // ==================== Combined Scenario Tests ====================

    @Test
    fun `calculate - high threat scenario - should give score above 0-75`() {
        // Realistic stalking scenario:
        // - 8 locations (high)
        // - Max distance 5km (significant)
        // - Over 2 days (persistent)
        // - Regular intervals (~6 hours)
        // - Phone device
        val device = createTestDevice("PHONE")
        val baseTime = System.currentTimeMillis()
        val locations = listOf(
            createLocation(40.0, -74.0, baseTime),
            createLocation(40.01, -74.01, baseTime + 6 * 60 * 60 * 1000),
            createLocation(40.02, -74.02, baseTime + 12 * 60 * 60 * 1000),
            createLocation(40.03, -74.03, baseTime + 18 * 60 * 60 * 1000),
            createLocation(40.04, -74.04, baseTime + 24 * 60 * 60 * 1000),
            createLocation(40.05, -74.05, baseTime + 30 * 60 * 60 * 1000),
            createLocation(40.06, -74.06, baseTime + 36 * 60 * 60 * 1000),
            createLocation(40.07, -74.07, baseTime + 48 * 60 * 60 * 1000)
        )
        val distances = createDistancesFromLocations(locations)

        val score = calculator.calculate(device, locations, distances)

        assertTrue("High threat scenario should score above 0.75", score >= 0.75)
    }

    @Test
    fun `calculate - low threat scenario - should give score below 0-5`() {
        // Likely coincidence scenario:
        // - 3 locations (minimum)
        // - Short distances (~200m)
        // - Short time span (< 1 hour)
        // - Headphones device
        val device = createTestDevice("HEADPHONES")
        val baseTime = System.currentTimeMillis()
        val locations = listOf(
            createLocation(40.0, -74.0, baseTime),
            createLocation(40.001, -74.001, baseTime + 20 * 60 * 1000), // ~100m, +20min
            createLocation(40.002, -74.002, baseTime + 40 * 60 * 1000)  // ~200m, +40min
        )
        val distances = createDistancesFromLocations(locations)

        val score = calculator.calculate(device, locations, distances)

        assertTrue("Low threat scenario should score below 0.5", score < 0.5)
    }

    @Test
    fun `calculateWithBreakdown - total should equal sum of factors`() {
        val device = createTestDevice("PHONE")
        val locations = createTestLocations(5, intervalHours = 2)
        val distances = createDistancesFromLocations(locations)

        val breakdown = calculator.calculateWithBreakdown(device, locations, distances)

        val sum = breakdown.locationCountScore +
                breakdown.distanceScore +
                breakdown.timeCorrelationScore +
                breakdown.consistencyScore +
                breakdown.deviceTypeScore

        assertEquals("Total score should equal sum of factors", sum, breakdown.totalScore, 0.001)
    }

    @Test
    fun `calculate - score should never exceed 1-0`() {
        // Extreme scenario that might try to exceed 1.0
        val device = createTestDevice("PHONE")
        val locations = createTestLocations(20, intervalHours = 1) // 20 locations
        val distances = List(100) { 50000.0 } // Unrealistically large distances

        val score = calculator.calculate(device, locations, distances)

        assertTrue("Score should never exceed 1.0", score <= 1.0)
    }

    @Test
    fun `calculate - score should never be negative`() {
        val device = createTestDevice("HEADPHONES")
        val locations = createTestLocations(2, intervalHours = 1)
        val distances = listOf(10.0) // Very short distance

        val score = calculator.calculate(device, locations, distances)

        assertTrue("Score should never be negative", score >= 0.0)
    }

    // ==================== Helper Methods ====================

    private fun createTestDevice(deviceType: String?): ScannedDevice {
        return ScannedDevice(
            id = 1,
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            firstSeen = System.currentTimeMillis(),
            lastSeen = System.currentTimeMillis(),
            detectionCount = 1,
            deviceType = deviceType
        )
    }

    private fun createLocation(
        latitude: Double,
        longitude: Double,
        timestamp: Long
    ): Location {
        return Location(
            id = 0,
            latitude = latitude,
            longitude = longitude,
            accuracy = 10.0f,
            altitude = null,
            timestamp = timestamp,
            provider = "GPS"
        )
    }

    private fun createTestLocations(
        count: Int,
        intervalHours: Long,
        baseLatitude: Double = 40.0,
        baseLongitude: Double = -74.0
    ): List<Location> {
        val baseTime = System.currentTimeMillis()
        return List(count) { i ->
            createLocation(
                latitude = baseLatitude + (i * 0.01), // ~1.1km per 0.01 degrees
                longitude = baseLongitude + (i * 0.01),
                timestamp = baseTime + (i * intervalHours * 60 * 60 * 1000)
            )
        }
    }

    private fun createDistancesFromLocations(locations: List<Location>): List<Double> {
        val distances = mutableListOf<Double>()
        for (i in locations.indices) {
            for (j in i + 1 until locations.size) {
                // Approximate distance calculation for testing
                val latDiff = locations[j].latitude - locations[i].latitude
                val lonDiff = locations[j].longitude - locations[i].longitude
                val distance = kotlin.math.sqrt(latDiff * latDiff + lonDiff * lonDiff) * 111000 // ~111km per degree
                distances.add(distance)
            }
        }
        return distances
    }
}
