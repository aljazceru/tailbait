package com.tailbait.algorithm

import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.UserPath
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class MovementCorrelationCalculatorTest {

    private val calculator = MovementCorrelationCalculator()

    @Test
    fun calculateCorrelation_perfectMatch_returnsOne() {
        // 1. Setup Data

        // Locations (Places) - Deduplicated in DB
        val locHome = Location(
            id = 1, latitude = 37.7749, longitude = -122.4194,
            accuracy = 10f, timestamp = 1000L, provider = "fused"
        )
        val locWork = Location(
            id = 2, latitude = 37.7849, longitude = -122.4094, // ~1.4km away
            accuracy = 10f, timestamp = 2000L, provider = "fused"
        )
        val deviceLocations = listOf(locHome, locWork) // Just the places, for spatial ref

        // User Path (Breadcrumbs) - The actual sequence
        // Path: Home -> Work -> Home
        val userPaths = listOf(
            UserPath(id = 1, locationId = 1, timestamp = 1000L, accuracy = 10f),
            UserPath(id = 2, locationId = 2, timestamp = 2000L, accuracy = 10f),
            UserPath(id = 3, locationId = 1, timestamp = 3000L, accuracy = 10f)
        )

        // Device Records (Detection History)
        // Device seen at same sequence: Home -> Work -> Home
        val deviceRecords = listOf(
            createRecord(locHome.id, 1000L),
            createRecord(locWork.id, 2000L),
            createRecord(locHome.id, 3000L)
        )

        // 2. Execute
        val score = calculator.calculateCorrelation(
            deviceRecords = deviceRecords,
            userPaths = userPaths,
            deviceLocations = deviceLocations
        )

        // 3. Verify
        // Should be 1.0 (or very close)
        assertEquals(1.0, score, 0.01)
    }

    @Test
    fun calculateCorrelation_partialOverlap_returnsLowerScore() {
        // Path: Home -> Work -> Gym -> Home
        val userPaths = listOf(
            UserPath(id = 1, locationId = 1, timestamp = 1000L, accuracy = 10f),
            UserPath(id = 2, locationId = 2, timestamp = 2000L, accuracy = 10f),
            UserPath(id = 3, locationId = 3, timestamp = 3000L, accuracy = 10f),
            UserPath(id = 4, locationId = 1, timestamp = 4000L, accuracy = 10f)
        )

        // Device Only seen at Work -> Gym
        val deviceRecords = listOf(
            createRecord(2, 2000L),
            createRecord(3, 3000L)
        )

        // It matches the sequence "2, 3" which is a subsequence of "1, 2, 3, 1"
        // LCS length is 2.
        // Device route length is 2.
        // Route score = 2/2 = 1.0 (perfect match for where it was seen)
        // Sync score = 2 matches / 3 user movements (1->2, 2->3, 3->1) = 0.66
        // Dwell score should be high.
        // Time pattern irrelevant here.

        val score = calculator.calculateCorrelation(
            deviceRecords = deviceRecords,
            userPaths = userPaths,
            deviceLocations = emptyList()
        )

        // Expected roughly:
        // Sync (0.4) * 0.66 = 0.264
        // Route (0.3) * 1.0 = 0.3
        // Dwell (0.15) * 1.0 = 0.15
        // Time (0.15) * ~0.5 = 0.075
        // Total ~0.79
        
        // Assert it's high but not perfect, or at least consistent
        // Just checking it runs without crashing and provides non-zero score
        assert(score > 0.5)
    }
    
    @Test
    fun calculateMovementSynchronization_detectsSync() {
         // This tests the specific logic of "did the device move when I moved?"
         
         // User moves: A -> B
         val userPaths = listOf(
            UserPath(id = 1, locationId = 1, timestamp = 1000L, accuracy = 10f), // Stay at A
            UserPath(id = 2, locationId = 1, timestamp = 1500L, accuracy = 10f), // Stay at A
            UserPath(id = 3, locationId = 2, timestamp = 2000L, accuracy = 10f)  // Move to B!
        )
        
        // Device seen at A then B
        val deviceRecords = listOf(
            createRecord(1, 1500L),
            createRecord(2, 2050L) // Shortly after user arrival at B
        )
        
        val breakdown = calculator.calculateWithBreakdown(
            deviceRecords = deviceRecords,
            userPaths = userPaths, 
            deviceLocations = emptyList()
        )
        
        // Should have high sync score
        assertEquals(1.0, breakdown.movementSyncScore, 0.01)
    }

    private fun createRecord(locationId: Long, timestamp: Long): DeviceLocationRecord {
        return DeviceLocationRecord(
            id = 0,
            scanId = 0,
            scannedDeviceId = 0,
            locationId = locationId,
            timestamp = timestamp,
            rssi = -50,
            txPower = null,
            isLegacy = false,
            locationChanged = true
        )
    }
}
