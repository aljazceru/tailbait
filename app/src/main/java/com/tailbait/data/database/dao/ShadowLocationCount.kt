package com.tailbait.data.database.dao

/**
 * DTO for shadow-based detection queries.
 * Represents how many distinct devices with a given shadow key
 * were seen at a specific location.
 *
 * Used by [ScannedDeviceDao.getShadowLocationDeviceCounts] and
 * consumed by [com.tailbait.algorithm.ShadowAnalyzer].
 */
data class ShadowLocationCount(
    val locationId: Long,
    val deviceCount: Int,
    val maxRssi: Int
)
