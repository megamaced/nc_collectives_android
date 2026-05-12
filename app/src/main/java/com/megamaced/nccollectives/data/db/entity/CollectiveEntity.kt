package com.megamaced.nccollectives.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collectives")
data class CollectiveEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val slug: String?,
    val emoji: String?,
    val canEdit: Boolean,
    val canShare: Boolean,
    val isPageShare: Boolean,
    val trashTimestamp: Long?,
    /** CSV of favorite-page ids; tiny lists, no need for a join table. */
    val userFavoritePagesCsv: String,
    val lastSyncedAt: Long,
)
