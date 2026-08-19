package com.tailbait.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tailbait.data.database.entities.CompanionDevice
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the companion device registry (paired ESP32 units).
 */
@Dao
interface CompanionDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: CompanionDevice): Long

    @Update
    suspend fun update(device: CompanionDevice)

    @Query("SELECT * FROM companion_devices ORDER BY created_at DESC")
    fun getAll(): Flow<List<CompanionDevice>>

    @Query("SELECT * FROM companion_devices LIMIT 1")
    fun getFirst(): Flow<CompanionDevice?>

    @Query("SELECT * FROM companion_devices WHERE address = :address LIMIT 1")
    suspend fun getByAddress(address: String): CompanionDevice?

    @Query("UPDATE companion_devices SET last_connected_at = :timestamp WHERE address = :address")
    suspend fun updateLastConnected(
        address: String,
        timestamp: Long,
    )

    @Query(
        "UPDATE companion_devices SET records_received = records_received + :count, " +
            "firmware_version = :firmware, mode = :mode, last_stats_json = :stats " +
            "WHERE address = :address",
    )
    suspend fun updateStats(
        address: String,
        count: Long,
        firmware: String?,
        mode: String?,
        stats: String?,
    )

    @Query("UPDATE companion_devices SET is_enabled = :enabled WHERE address = :address")
    suspend fun updateEnabled(
        address: String,
        enabled: Boolean,
    )

    @Query("DELETE FROM companion_devices WHERE address = :address")
    suspend fun deleteByAddress(address: String)

    @Delete
    suspend fun delete(device: CompanionDevice)
}
