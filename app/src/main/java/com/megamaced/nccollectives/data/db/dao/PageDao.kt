package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.PageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {
    @Query(
        "SELECT * FROM pages WHERE collectiveId = :collectiveId AND trashTimestamp IS NULL " +
            "ORDER BY title COLLATE NOCASE ASC",
    )
    fun observeForCollective(collectiveId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getById(id: Long): PageEntity?

    @Upsert
    suspend fun upsertAll(pages: List<PageEntity>)

    @Query("UPDATE pages SET bodyMd = :body, bodyEtag = :etag, lastSyncedAt = :syncedAt WHERE id = :id")
    suspend fun updateBody(
        id: Long,
        body: String,
        etag: String?,
        syncedAt: Long,
    )

    @Query("DELETE FROM pages WHERE collectiveId = :collectiveId AND id NOT IN (:keepIds)")
    suspend fun deleteMissingForCollective(
        collectiveId: Long,
        keepIds: List<Long>,
    )

    @Query("DELETE FROM pages")
    suspend fun clear()
}
