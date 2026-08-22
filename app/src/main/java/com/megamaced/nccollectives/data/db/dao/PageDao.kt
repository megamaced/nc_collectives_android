package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.PageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Projection returned by [PageDao.collectiveIdsForPages] — the page-to-
 * collective mapping with none of the row's body columns. Not an entity:
 * a query projection carries no schema of its own.
 */
data class PageCollectiveRef(
    val id: Long,
    val collectiveId: Long,
)

@Dao
interface PageDao {
    @Query(
        "SELECT * FROM pages WHERE collectiveId = :collectiveId AND trashTimestamp IS NULL " +
            "ORDER BY title COLLATE NOCASE ASC",
    )
    fun observeForCollective(collectiveId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getById(id: Long): PageEntity?

    /**
     * R-27: bulk read of every cached row for a collective. Used by
     * `refresh()` to look up existing body / etag / draft in one query
     * instead of `getById(dto.id)` N times.
     */
    @Query("SELECT * FROM pages WHERE collectiveId = :collectiveId")
    suspend fun listForCollective(collectiveId: Long): List<PageEntity>

    /**
     * R-46: which collective each of [ids] belongs to, in one query.
     * Search-hit mapping needs nothing but that one column, and [getById]
     * is a `SELECT *` that drags the whole cached markdown body along —
     * a 20-result search meant 20 body-sized reads to fetch 20 Longs.
     *
     * Callers must skip the call on an empty [ids]: Room expands the
     * binding to `IN ()`, which is a SQLite syntax error (same trap as
     * B-42 in `AttachmentRepositoryImpl`).
     */
    @Query("SELECT id, collectiveId FROM pages WHERE id IN (:ids)")
    suspend fun collectiveIdsForPages(ids: List<Long>): List<PageCollectiveRef>

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
