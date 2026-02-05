package com.tailbait.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey



/**
 * Entity representing a whitelisted (trusted) BLE device.
 *
 * This entity stores information about devices that have been explicitly marked as
 * trusted by the user. Whitelisted devices are excluded from stalking detection
 * algorithms. Each device can only have one whitelist entry (enforced via unique
 * index on device_id).
 *
 * Devices can be whitelisted manually or through Learn Mode, and are categorized
 * to help users organize their trusted devices.
 *
 * @property id Auto-generated primary key for this whitelist entry
 * @property deviceId Foreign key referencing the ScannedDevice being whitelisted
 * @property label User-assigned label for the device (e.g., "My Phone", "Partner's Watch")
 * @property category Classification category ("OWN", "PARTNER", "TRUSTED")
 * @property addedViaLearnMode Flag indicating if device was added through Learn Mode
 * @property notes Optional user notes about this whitelisted device
 * @property createdAt Timestamp in milliseconds when this whitelist entry was created
 */
@Entity(
    tableName = "whitelist_entries",
    foreignKeys = [
        ForeignKey(
            entity = ScannedDevice::class,
            parentColumns = ["id"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["device_id"], unique = true)]
)

data class WhitelistEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "device_id")
    val deviceId: Long,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "added_via_learn_mode")
    val addedViaLearnMode: Boolean = false,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
