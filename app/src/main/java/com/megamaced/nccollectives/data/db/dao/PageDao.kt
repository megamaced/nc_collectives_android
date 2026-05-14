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

    @Query("SELECT * FROM pages WHERE id = :id")
    fun observeById(id: Long): Flow<PageEntity?>

    @Query("SELECT id FROM pages WHERE title = :title LIMIT 1")
    suspend fun findIdByTitle(title: String): Long?

    @Query(
        "SELECT id FROM pages WHERE collectiveId = :collectiveId " +
            "AND title = :title COLLATE NOCASE AND trashTimestamp IS NULL LIMIT 1",
    )
    suspend fun findIdByTitleInCollective(
        collectiveId: Long,
        title: String,
    ): Long?

    @Query(
        "SELECT * FROM pages WHERE trashTimestamp IS NULL " +
            "ORDER BY serverTimestamp DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE id IN (:ids) AND trashTimestamp IS NULL")
    fun observeByIds(ids: List<Long>): Flow<List<PageEntity>>

    @Upsert
    suspend fun upsertAll(pages: List<PageEntity>)

    @Query("UPDATE pages SET bodyMd = :body, bodyEtag = :etag, lastSyncedAt = :syncedAt WHERE id = :id")
    suspend fun updateBody(
        id: Long,
        body: String,
        etag: String?,
        syncedAt: Long,
    )

    @Query("UPDATE pages SET draftBodyMd = :draft WHERE id = :id")
    suspend fun updateDraft(
        id: Long,
        draft: String?,
    )

    @Query("UPDATE pages SET emoji = :emoji WHERE id = :id")
    suspend fun updateEmoji(
        id: Long,
        emoji: String?,
    )

    @Query("UPDATE pages SET tagsCsv = :csv WHERE id = :id")
    suspend fun updateTagsCsv(
        id: Long,
        csv: String,
    )

    @Query(
        "UPDATE pages SET title = :title, fileName = :fileName, filePath = :filePath WHERE id = :id",
    )
    suspend fun updateTitleAndPath(
        id: Long,
        title: String,
        fileName: String,
        filePath: String,
    )

    @Query("UPDATE pages SET parentId = :parentId, filePath = :filePath WHERE id = :id")
    suspend fun updateParentAndPath(
        id: Long,
        parentId: Long,
        filePath: String,
    )

    @Query("DELETE FROM pages WHERE collectiveId = :collectiveId AND id NOT IN (:keepIds)")
    suspend fun deleteMissingForCollective(
        collectiveId: Long,
        keepIds: List<Long>,
    )

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pages")
    suspend fun clear()
}
