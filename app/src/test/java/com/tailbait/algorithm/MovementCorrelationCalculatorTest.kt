package com.tailbait.algorithm

import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.UserPath
import org.junit.Assert.assertEquals
import org.junit.Test

class MovementCorrelationCalculatorTest {
    private val calculator = MovementCorrelationCalculator()

    @Test
    fun calculateCorrelation_perfectMatch_returnsOne() {
        // 1. Setup Data

        // Locations (Places) - Deduplicated in DB
        val locHome =
            Location(
                id = 1,
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10f,
                timestamp = 1000L,
                provider = "fused",
            )
        val locWork =
            Location(
                id = 2,
                latitude = 37.7849,
                // ~1.4km away
                longitude = -122.4094,
                accuracy = 10f,
                timestamp = 2000L,
                provider = "fused",
            )
        val deviceLocations = listOf(locHome, locWork) // Just the places, for spatial ref

        // User Path (Breadcrumbs) - The actual sequence
        // Path: Home -> Work -> Home
        val userPaths =
            listOf(
                UserPath(id = 1, locationId = 1, timestamp = 1000L, accuracy = 10f),
                UserPath(id = 2, locationId = 2, timestamp = 2000L, accuracy = 10f),
                UserPath(id = 3, locationId = 1, timestamp = 3000L, accuracy = 10f),
            )

        // Device Records (Detection History)
        // Device seen at same sequence: Home -> Work -> Home
        val deviceRecords =
            listOf(
                createRecord(locHome.id, 1000L),
                createRecord(locWork.id, 2000L),
                createRecord(locHome.id, 3000L),
            )

        // 2. Execute
        val score =
            calculator.calculateCorrelation(
                deviceRecords = deviceRecords,
                userPaths = userPaths,
                deviceLocations = deviceLocations,
            )

        // 3. Verify
        // Should be 1.0 (or very close)
        assertEquals(1.0, score, 0.01)
    }

    @Test
    fun calculateCorrelation_partialOverlap_returnsLowerScore() {
        // Path: Home -> Work -> Gym -> Home
        val userPaths =
            listOf(
                UserPath(id = 1, locationId = 1, timestamp = 1000L, accuracy = 10f),
                UserPath(id = 2, locationId = 2, timestamp = 2000L, accuracy = 10f),
                UserPath(id = 3, locationId = 3, timestamp = 3000L, accuracy = 10f),
                UserPath(id = 4, locationId = 1, timestamp = 4000L, accuracy = 10f),
            )

        // Device seen at Work -> Gym -> unknown location (not on user's route)
        // Need at least MIN_RECORDS_FOR_ANALYSIS (3) device records
        val deviceRecords =
            listOf(
                createRecord(2, 2000L),
                createRecord(3, 3000L),
                // Location 4 is not in the user's path
                createRecord(4, 5000L),
            )

        // Route: userRoute=[1,2,3,1], deviceRoute=[2,3,4]
        //   LCS of [1,2,3,1] and [2,3,4] = 2 (matching 2,3)
        //   routeScore = 2/3 = 0.667 (device went somewhere user didn't)
        // Sync: 3 user movements (1->2 @2000, 2->3 @3000, 3->1 @4000)
        //   All have device records within SYNC_WINDOW_MS -> syncScore = 1.0
        // Dwell: shared locations 2,3 both match -> dwellScore = 1.0
        // Time: all records at hour 0 -> timeScore = 1.0
        // Total = 1.0*0.4 + 0.667*0.3 + 1.0*0.2 + 1.0*0.1 = 0.9

        val score =
            calculator.calculateCorrelation(
                deviceRecords = deviceRecords,
                userPaths = userPaths,
                deviceLocations = emptyList(),
            )

        // Score should be high but not perfect due to route divergence
        assert(score > 0.5)
        assert(score < 1.0)
    }

    @Test
    fun calculateMovementSynchronization_detectsSync() {
        // This tests the specific logic of "did the device move when I moved?"

        // User moves: A -> A -> B -> B (need at least MIN_RECORDS_FOR_ANALYSIS=3 userPaths)
        val userPaths =
            listOf(
                // Stay at A
                UserPath(id = 1, locationId = 1, timestamp = 1000L, accuracy = 10f),
                // Stay at A
                UserPath(id = 2, locationId = 1, timestamp = 1500L, accuracy = 10f),
                // Move to B!
                UserPath(id = 3, locationId = 2, timestamp = 2000L, accuracy = 10f),
                // Stay at B
                UserPath(id = 4, locationId = 2, timestamp = 2500L, accuracy = 10f),
            )

        // Device seen at A, then at A again, then at B (need at least 3 records)
        // All timestamps within SYNC_WINDOW_MS (300,000ms) of the user movement at t=2000
        val deviceRecords =
            listOf(
                // At A when user is at A
                createRecord(1, 1000L),
                // At A when user is at A
                createRecord(1, 1500L),
                // At B shortly after user moves to B
                createRecord(2, 2050L),
            )

        val breakdown =
            calculator.calculateWithBreakdown(
                deviceRecords = deviceRecords,
                userPaths = userPaths,
                _deviceLocations = emptyList(),
            )

        // User has one movement: locationId change from 1->2 at timestamp 2000
        // Device record at 2050 is within SYNC_WINDOW_MS of 2000
        // syncScore = 1/1 matched movements = 1.0
        assertEquals(1.0, breakdown.movementSyncScore, 0.01)
    }

    private fun createRecord(
        locationId: Long,
        timestamp: Long,
    ): DeviceLocationRecord {
        return DeviceLocationRecord(
            id = 0,
            deviceId = 0,
            locationId = locationId,
            timestamp = timestamp,
            rssi = -50,
            scanTriggerType = "PERIODIC",
        )
    }
}
