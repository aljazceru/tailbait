package com.tailbait.algorithm

import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.UserPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                userLocations = deviceLocations,
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
        val locations = (1L..4L).map { createLocation(it) }

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
                deviceLocations = locations,
                userLocations = locations,
            )

        // Score should be high but not perfect due to route divergence
        assertTrue(score > 0.5)
        assertTrue(score < 1.0)
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
        val locations = listOf(createLocation(1), createLocation(2))

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
                deviceLocations = locations,
                userLocations = locations,
            )

        // User has one movement: locationId change from 1->2 at timestamp 2000
        // Device record at 2050 is within SYNC_WINDOW_MS of 2000
        // syncScore = 1/1 matched movements = 1.0
        assertEquals(1.0, breakdown.movementSyncScore, 0.01)
    }

    @Test
    fun calculateWithBreakdown_overlappingAccuracyRegions_doNotScoreSpatialMovement() {
        val locations = (1L..3L).map { createLocation(it) }
        val paths = createUserPaths(locations)
        val records = paths.map { createRecord(it.locationId, it.timestamp) }
        val accurate = calculator.calculateWithBreakdown(records, paths, locations, locations)
        val uncertainLocations = locations.map { it.copy(accuracy = 200f) }
        val uncertainPaths = paths.map { it.copy(accuracy = 200f) }

        for (
        (placeFixes, pathFixes) in
        listOf(
            uncertainLocations to uncertainPaths,
            locations to uncertainPaths,
            uncertainLocations to paths,
        )
        ) {
            val uncertain = calculator.calculateWithBreakdown(records, pathFixes, placeFixes, placeFixes)

            assertEquals(0.0, uncertain.movementSyncScore, 0.0)
            assertEquals(0.0, uncertain.routeOverlapScore, 0.0)
            assertEquals(accurate.dwellTimeScore, uncertain.dwellTimeScore, 0.0)
            assertEquals(accurate.timePatternScore, uncertain.timePatternScore, 0.0)
            assertEquals(0.7, accurate.totalScore - uncertain.totalScore, 0.0001)
        }

        assertEquals(1.0, accurate.movementSyncScore, 0.0)
        assertEquals(1.0, accurate.routeOverlapScore, 0.0)
    }

    @Test
    fun calculateWithBreakdown_invalidAccuracy_doesNotProveMovement() {
        val locations = (1L..3L).map { createLocation(it) }
        val paths = createUserPaths(locations)
        val records = paths.map { createRecord(it.locationId, it.timestamp) }

        for (accuracy in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val invalidPaths = paths.map { it.copy(accuracy = accuracy) }
            val invalidLocations = locations.map { it.copy(accuracy = accuracy) }
            val pathResult = calculator.calculateWithBreakdown(records, invalidPaths, locations, locations)
            val placeResult = calculator.calculateWithBreakdown(records, paths, invalidLocations, invalidLocations)

            for (result in listOf(pathResult, placeResult)) {
                assertEquals("Invalid accuracy $accuracy must not prove synchronization", 0.0, result.movementSyncScore, 0.0)
                assertEquals("Invalid accuracy $accuracy must not prove a route", 0.0, result.routeOverlapScore, 0.0)
                assertEquals(0.3, result.totalScore, 0.0001)
            }
        }
    }

    @Test
    fun calculateWithBreakdown_missingCoordinates_timestampsDoNotProveMovement() {
        val locations = (1L..3L).map { createLocation(it) }
        val paths = createUserPaths(locations)
        val records = paths.map { createRecord(it.locationId, it.timestamp) }

        val result = calculator.calculateWithBreakdown(records, paths, emptyList(), emptyList())

        assertEquals(0.0, result.movementSyncScore, 0.0)
        assertEquals(0.0, result.routeOverlapScore, 0.0)
    }

    @Test
    fun calculateWithBreakdown_invalidUnmatchedTransition_cannotIncreaseConfidence() {
        val locations = (1L..3L).map { createLocation(it) }
        val paths =
            createUserPaths(locations).mapIndexed { index, path ->
                path.copy(timestamp = index * 600_000L)
            }
        val records =
            listOf(
                createRecord(1, 0L),
                createRecord(2, 600_000L),
                createRecord(2, 600_001L),
            )
        val accurate = calculator.calculateWithBreakdown(records, paths, locations, locations)
        val invalidPaths = paths.map { if (it.locationId == 3L) it.copy(accuracy = Float.NaN) else it }

        val uncertain = calculator.calculateWithBreakdown(records, invalidPaths, locations, locations)

        assertEquals(0.5, accurate.movementSyncScore, 0.0)
        assertEquals(accurate.movementSyncScore, uncertain.movementSyncScore, 0.0)
        assertTrue(uncertain.totalScore <= accurate.totalScore)
    }

    private fun createLocation(id: Long): Location {
        return Location(
            id = id,
            latitude = (id - 1) * 0.001,
            longitude = 0.0,
            accuracy = 10f,
            timestamp = id * 1000L,
            provider = "fused",
            createdAt = 0L,
        )
    }

    private fun createUserPaths(locations: List<Location>): List<UserPath> {
        return locations.map {
            UserPath(
                id = it.id,
                locationId = it.id,
                timestamp = it.timestamp,
                accuracy = it.accuracy,
                createdAt = 0L,
            )
        }
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
