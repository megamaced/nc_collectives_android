package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE pageId = :pageId ORDER BY lastModifiedMs DESC, fileName ASC")
    fun observeForPage(pageId: Long): Flow<List<AttachmentEntity>>

    /** One-shot snapshot of [observeForPage] for repository-side reconciliation. */
    @Query("SELECT * FROM attachments WHERE pageId = :pageId")
    suspend fun listForPage(pageId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getById(id: String): AttachmentEntity?

    @Query(
        "SELECT * FROM attachments WHERE status IN ('PENDING', 'UPLOADING') ORDER BY lastSyncedAt ASC",
    )
    suspend fun pendingUploads(): List<AttachmentEntity>

    @Upsert
    suspend fun upsert(entity: AttachmentEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AttachmentEntity>)

    @Query("UPDATE attachments SET status = :status WHERE id = :id")
    suspend fun setStatus(
        id: String,
        status: String,
    )

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        "DELETE FROM attachments WHERE pageId = :pageId AND status = 'REMOTE' AND id NOT IN (:keepIds)",
    )
    suspend fun deleteMissingRemoteForPage(
        pageId: Long,
        keepIds: List<String>,
    )

    @Query("DELETE FROM attachments WHERE pageId IN (:pageIds)")
    suspend fun deleteForPageIds(pageIds: List<Long>)

    /** B-42: per-page REMOTE-row clear for the empty-keep-list short-circuit. */
    @Query("DELETE FROM attachments WHERE pageId = :pageId AND status = 'REMOTE'")
    suspend fun deleteRemoteForPage(pageId: Long)

    @Query("DELETE FROM attachments")
    suspend fun clear()
}
