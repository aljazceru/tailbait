package com.tailbait.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Paired TailBait companion device (ESP32 firmware: tailbait-companion).
 *
 * Registry entry created when the user pairs their phone with a companion
 * over BLE GATT (service a7f00001-e8a4-4b0e-a1c3-7461696c6261). Holds
 * connection bookkeeping and the last known firmware/mode state.
 *
 * @property address BLE MAC of the companion
 * @property name Device name (from advertisement, e.g. "TailBait-Companion")
 * @property firmwareVersion e.g. "0.2"
 * @property mode Last reported mode: CARRY | SENTINEL
 * @property isEnabled Whether the app should connect while tracking runs
 * @property recordsReceived Lifetime count of records ingested from this device
 * @property lastConnectedAt Epoch ms of last successful GATT connection
 * @property lastStatsJson Last STATUS snapshot (debug/diagnostics)
 */
@Entity(
    tableName = "companion_devices",
    indices = [Index(value = ["address"], unique = true)],
)
data class CompanionDevice(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "address")
    val address: String,
    @ColumnInfo(name = "name")
    val name: String? = null,
    @ColumnInfo(name = "firmware_version")
    val firmwareVersion: String? = null,
    @ColumnInfo(name = "mode")
    val mode: String? = null,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    @ColumnInfo(name = "records_received")
    val recordsReceived: Long = 0,
    @ColumnInfo(name = "last_connected_at")
    val lastConnectedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_stats_json")
    val lastStatsJson: String? = null,
)
