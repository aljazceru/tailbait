package com.tailbait.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey



/**
 * Entity representing a many-to-many relationship between devices and locations.
 *
 * This junction entity records each detection event of a BLE device at a specific
 * location. It captures contextual information about the detection including signal
 * strength (RSSI), location changes, and scan trigger type. Multiple indices are
 * maintained for efficient querying of device-location correlations.
 *
 * The entity implements cascade delete behavior - if either the device or location
 * is deleted, all associated records are automatically removed.
 *
 * @property id Auto-generated primary key for this detection record
 * @property deviceId Foreign key referencing the ScannedDevice
 * @property locationId Foreign key referencing the Location
 * @property rssi Received Signal Strength Indicator in dBm (typically -100 to 0)
 * @property timestamp Timestamp in milliseconds when this detection occurred
 * @property scanDurationMs Duration in milliseconds that the device was in range during
 * this scan
 * @property locationChanged Boolean flag indicating if location changed since last scan
 * @property distanceFromLast Distance in meters from previous detection location (nullable
 * for first detection)
 * @property scanTriggerType Type of scan that detected this device ("MANUAL", "CONTINUOUS",
 * "PERIODIC", "LOCATION_BASED")
 * @property createdAt Timestamp in milliseconds when this database record was created
 */
@Entity(
    tableName = "device_location_records",
    foreignKeys = [
        ForeignKey(
            entity = ScannedDevice::class,
            parentColumns = ["id"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Location::class,
            parentColumns = ["id"],
            childColumns = ["location_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["device_id"]),
        Index(value = ["location_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["device_id", "location_id"], unique = false)
    ]
)

data class DeviceLocationRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "device_id")
    val deviceId: Long,

    @ColumnInfo(name = "location_id")
    val locationId: Long,

    @ColumnInfo(name = "rssi")
    val rssi: Int,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "scan_duration_ms")
    val scanDurationMs: Long = 0,

    @ColumnInfo(name = "location_changed")
    val locationChanged: Boolean = false,

    @ColumnInfo(name = "distance_from_last")
    val distanceFromLast: Double? = null,

    @ColumnInfo(name = "scan_trigger_type")
    val scanTriggerType: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
