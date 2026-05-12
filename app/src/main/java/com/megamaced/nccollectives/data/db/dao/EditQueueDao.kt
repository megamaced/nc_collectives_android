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

    @Query("UPDATE edit_queue SET status = :status WHERE id = :id")
    suspend fun setStatus(
        id: Long,
        status: String,
    )

    @Query("DELETE FROM edit_queue WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM edit_queue WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: Long)
}
