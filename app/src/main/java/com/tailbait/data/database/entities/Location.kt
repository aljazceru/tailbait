package com.tailbait.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey



/**
 * Entity representing a GPS location point where device scanning occurred.
 *
 * This entity stores GPS coordinates and associated metadata for locations where
 * BLE device scans were performed. Locations are indexed by timestamp to enable
 * efficient temporal queries for device tracking analysis.
 *
 * @property id Auto-generated primary key for the location record
 * @property latitude Latitude coordinate in decimal degrees
 * @property longitude Longitude coordinate in decimal degrees
 * @property accuracy Location accuracy in meters - indicates the precision of the GPS fix
 * @property altitude Altitude in meters above sea level (nullable if not available)
 * @property timestamp Timestamp in milliseconds when this location was captured
 * @property provider Location provider source (e.g., "GPS", "NETWORK", "FUSED")
 * @property createdAt Timestamp in milliseconds when this database record was created
 */
@Entity(
    tableName = "locations",
    indices = [Index(value = ["timestamp"])]
)

data class Location(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,

    @ColumnInfo(name = "accuracy")
    val accuracy: Float,

    @ColumnInfo(name = "altitude")
    val altitude: Double? = null,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "provider")
    val provider: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
