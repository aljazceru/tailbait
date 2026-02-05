package com.tailbait.data.dto

/**
 * Data class for optimized map data query result.
 * Used to avoid N+1 queries when loading the map.
 */
data class DeviceLocationMapData(
    val id: Long,
    val deviceId: Long,
    val locationId: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val rssi: Int,
    val deviceAddress: String,
    val deviceType: String?,
    val manufacturerData: String?
)
