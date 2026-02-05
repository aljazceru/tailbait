package com.tailbait.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey



/**
 * Entity representing a point in the user's movement history path.
 *
 * Unlike the [Location] entity which represents unique "Places" (clusters),
 * UserPath represents the raw sequence of movements (Breadcrumbs).
 * This preserves the order of visitation (e.g., Home -> Work -> Home) which
 * is critical for movement correlation algorithms.
 *
 * @property id Auto-generated primary key
 * @property locationId Reference to the "Place" (Location entity) this path point corresponds to
 * @property timestamp When the user was at this point
 * @property accuracy GPS accuracy in meters
 * @property createdAt Record creation time
 */
@Entity(
    tableName = "user_path",
    foreignKeys = [
        ForeignKey(
            entity = Location::class,
            parentColumns = ["id"],
            childColumns = ["location_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["location_id"]),
        Index(value = ["timestamp"])
    ]
)

data class UserPath(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "location_id")
    val locationId: Long,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "accuracy")
    val accuracy: Float,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
