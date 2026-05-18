package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity

@Dao
interface EditQueueDao {
    @Upsert
    suspend fun upsert(entry: EditQueueEntity)

    @Query("SELECT * FROM edit_queue WHERE status != 'CONFLICTED' ORDER BY queuedAt ASC")
    suspend fun pendingEntries(): List<EditQueueEntity>

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
