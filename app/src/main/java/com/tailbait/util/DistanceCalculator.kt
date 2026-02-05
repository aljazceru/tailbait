package com.tailbait.util

import kotlin.math.*

/**
 * Utility object for calculating distances between GPS coordinates.
 *
 * Uses the Haversine formula to calculate great-circle distances between
 * two points on a sphere given their longitudes and latitudes.
 */
object DistanceCalculator {
    // Earth's mean radius in meters
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculate the distance between two GPS coordinates using the Haversine formula.
     *
     * @param lat1 Latitude of the first point in decimal degrees
     * @param lon1 Longitude of the first point in decimal degrees
     * @param lat2 Latitude of the second point in decimal degrees
     * @param lon2 Longitude of the second point in decimal degrees
     * @return Distance in meters
     */
    fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        // Convert latitude and longitude from degrees to radians
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)

        // Haversine formula
        val a = sin(deltaLat / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        // Distance in meters
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculate the distance between two locations.
     *
     * @param location1 First location
     * @param location2 Second location
     * @return Distance in meters
     */
    fun calculateDistance(
        location1: com.tailbait.data.database.entities.Location,
        location2: com.tailbait.data.database.entities.Location
    ): Double {
        return calculateDistance(
            location1.latitude,
            location1.longitude,
            location2.latitude,
            location2.longitude
        )
    }

    /**
     * Check if two locations are within a specified distance threshold.
     *
     * @param lat1 Latitude of the first point
     * @param lon1 Longitude of the first point
     * @param lat2 Latitude of the second point
     * @param lon2 Longitude of the second point
     * @param thresholdMeters Distance threshold in meters
     * @return True if the distance is less than or equal to the threshold
     */
    fun isWithinDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        thresholdMeters: Double
    ): Boolean {
        return calculateDistance(lat1, lon1, lat2, lon2) <= thresholdMeters
    }

    /**
     * Calculate bearing (direction) from one point to another.
     *
     * @param lat1 Latitude of the first point in decimal degrees
     * @param lon1 Longitude of the first point in decimal degrees
     * @param lat2 Latitude of the second point in decimal degrees
     * @param lon2 Longitude of the second point in decimal degrees
     * @return Bearing in degrees (0-360, where 0 is North)
     */
    fun calculateBearing(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)
        val bearingRad = atan2(y, x)
        val bearingDeg = Math.toDegrees(bearingRad)

        // Normalize to 0-360
        return (bearingDeg + 360) % 360
    }
}
