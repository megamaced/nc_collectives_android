package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity

@Dao
interface EditQueueDao {
    @Upsert
    suspend fun upsert(entry: EditQueueEntity)

    /**
     * R-26: hard-cap the worker's drain at [limit] rows so an unexpectedly
     * huge offline backlog (extended airplane mode, share-spam against a
     * dropped network) doesn't pull the entire queue into memory + hold a
     * single transaction open for the whole run. Subsequent worker runs
     * pick up the next batch.
     */
    @Query("SELECT * FROM edit_queue WHERE status != 'CONFLICTED' ORDER BY queuedAt ASC LIMIT :limit")
    suspend fun pendingEntries(limit: Int = 100): List<EditQueueEntity>

    @Query("SELECT * FROM edit_queue WHERE pageId = :pageId LIMIT 1")
    suspend fun forPage(pageId: Long): EditQueueEntity?

    @Query("UPDATE edit_queue SET status = :status WHERE pageId = :pageId")
    suspend fun setStatus(
        pageId: Long,
        status: String,
    )

    @Query("DELETE FROM edit_queue WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: Long)

    @Query("DELETE FROM edit_queue WHERE pageId IN (:pageIds)")
    suspend fun deleteForPageIds(pageIds: List<Long>)

    @Query("DELETE FROM edit_queue")
    suspend fun clear()
}
