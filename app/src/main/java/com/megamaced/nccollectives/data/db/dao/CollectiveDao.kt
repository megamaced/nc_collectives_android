package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.CollectiveEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectiveDao {
    @Query("SELECT * FROM collectives WHERE trashTimestamp IS NULL ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CollectiveEntity>>

    @Query("SELECT * FROM collectives WHERE id = :id")
    suspend fun getById(id: Long): CollectiveEntity?

    @Query("UPDATE collectives SET userFavoritePagesCsv = :csv WHERE id = :id")
    suspend fun updateFavoritePagesCsv(
        id: Long,
        csv: String,
    )

    @Upsert
    suspend fun upsertAll(collectives: List<CollectiveEntity>)

    @Query("DELETE FROM collectives WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Long>)

    @Query("DELETE FROM collectives")
    suspend fun clear()
}
