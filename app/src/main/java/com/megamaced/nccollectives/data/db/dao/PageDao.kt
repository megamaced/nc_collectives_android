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

    @Query(
        "SELECT id FROM pages WHERE collectiveId = :collectiveId " +
            "AND title = :title COLLATE NOCASE AND trashTimestamp IS NULL LIMIT 1",
    )
    suspend fun findIdByTitleInCollective(
        collectiveId: Long,
        title: String,
    ): Long?

    @Query(
        "SELECT * FROM pages WHERE collectiveId = :collectiveId AND trashTimestamp IS NULL " +
            "AND parentId != 0 ORDER BY serverTimestamp DESC LIMIT :limit",
    )
    fun observeRecentInCollective(
        collectiveId: Long,
        limit: Int,
    ): Flow<List<PageEntity>>

    /**
     * Pages in [collectiveId] whose `tagsCsv` contains [tagName] (Batch 25).
     * `tagsCsv` stores tag *names* separated by U+001F; wrapping the column
     * with the separator on both sides lets a single LIKE pattern match
     * regardless of whether the tag sits first/middle/last/alone. The
     * pattern + sep wrapping are passed in from the repository so the SQL
     * stays opaque to the choice of separator character.
     */
    @Query(
        "SELECT * FROM pages WHERE collectiveId = :collectiveId AND trashTimestamp IS NULL " +
            "AND (:sep || tagsCsv || :sep) LIKE :likePattern ESCAPE '\\' " +
            "ORDER BY title COLLATE NOCASE ASC",
    )
    fun observePagesWithTagInCollective(
        collectiveId: Long,
        sep: String,
        likePattern: String,
    ): Flow<List<PageEntity>>

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

    @Query("UPDATE pages SET subpageOrderCsv = :csv WHERE id = :id")
    suspend fun updateSubpageOrderCsv(
        id: Long,
        csv: String,
    )

    @Query("DELETE FROM pages WHERE collectiveId = :collectiveId AND id NOT IN (:keepIds)")
    suspend fun deleteMissingForCollective(
        collectiveId: Long,
        keepIds: List<Long>,
    )

    @Query("SELECT id FROM pages WHERE collectiveId = :collectiveId")
    suspend fun idsForCollective(collectiveId: Long): List<Long>

    @Query("DELETE FROM pages WHERE collectiveId = :collectiveId")
    suspend fun deleteForCollective(collectiveId: Long)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pages")
    suspend fun clear()
}
