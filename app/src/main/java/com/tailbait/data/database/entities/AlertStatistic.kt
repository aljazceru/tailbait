package com.tailbait.data.database.entities

import androidx.room.ColumnInfo

/**
 * Data class representing alert statistics by severity level.
 * Used for querying alert counts grouped by alert level.
 */
data class AlertStatistic(
    @ColumnInfo(name = "alert_level")
    val alertLevel: String,

    @ColumnInfo(name = "count")
    val count: Int
)
