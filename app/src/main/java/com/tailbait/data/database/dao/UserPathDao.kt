package com.tailbait.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.tailbait.data.database.entities.UserPath
import kotlinx.coroutines.flow.Flow

@Dao
interface UserPathDao {
    @Insert
    suspend fun insert(userPath: UserPath): Long

    @Query("SELECT * FROM user_path ORDER BY timestamp DESC")
    fun getAllUserPaths(): Flow<List<UserPath>>

    @Query("SELECT * FROM user_path WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getUserPathSince(since: Long): List<UserPath>

    @Query("DELETE FROM user_path WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldPaths(beforeTimestamp: Long): Int

    @Query("DELETE FROM user_path")
    suspend fun deleteAll()
}
