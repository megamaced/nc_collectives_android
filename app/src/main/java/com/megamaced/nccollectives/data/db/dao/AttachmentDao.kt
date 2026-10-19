package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    /**
     * What the attachments grid shows. `DELETING` rows are excluded: the user
     * has already been told the attachment is gone, and the row only survives
     * so the worker can remove it from the server too (issue #35).
     */
    @Query(
        "SELECT * FROM attachments WHERE pageId = :pageId AND status != 'DELETING' " +
            "ORDER BY lastModifiedMs DESC, fileName ASC",
    )
    fun observeForPage(pageId: Long): Flow<List<AttachmentEntity>>

    /** One-shot snapshot of [observeForPage] for repository-side reconciliation. */
    @Query("SELECT * FROM attachments WHERE pageId = :pageId")
    suspend fun listForPage(pageId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getById(id: String): AttachmentEntity?

    /**
     * Rows the upload worker has work to do on. `DELETING` is in the set
     * because resolving a tombstone — the WebDAV DELETE that undoes a
     * cancelled upload — is the worker's job too (issue #35).
     */
    @Query(
        "SELECT * FROM attachments WHERE status IN ('PENDING', 'UPLOADING', 'DELETING') " +
            "ORDER BY lastSyncedAt ASC",
    )
    suspend fun pendingUploads(): List<AttachmentEntity>

    @Upsert
    suspend fun upsert(entity: AttachmentEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AttachmentEntity>)

    /**
     * Claim a row for an upload attempt: `UPLOADING`, and one more attempt
     * spent. See `EditQueueDao.markInFlight` — issue #30.
     *
     * Only a row still waiting, or one a crashed run left claimed: 0 when
     * it was cancelled or settled since the worker read the queue (issue
     * #63), and the worker must not send it.
     */
    @Query(
        "UPDATE attachments SET status = 'UPLOADING', attempts = attempts + 1 " +
            "WHERE id = :id AND status IN ('PENDING', 'UPLOADING')",
    )
    suspend fun markUploading(id: String): Int

    /**
     * The worker's settlement of a row: [status] unless the user cancelled
     * it meanwhile, whose tombstone stands (issue #63). 0 when it did.
     */
    @Query("UPDATE attachments SET status = :status WHERE id = :id AND status != 'DELETING'")
    suspend fun setStatusUnlessCancelled(
        id: String,
        status: String,
    ): Int

    /**
     * Record a landed upload as `REMOTE`, in one statement so a
     * cancellation can't fall between reading the row and writing it
     * (issue #63). 0 when the row was cancelled or has gone.
     */
    @Query(
        "UPDATE attachments SET status = 'REMOTE', contentType = :contentType, size = :size, etag = :etag, " +
            "lastModifiedMs = :at, localUriString = NULL, lastSyncedAt = :at WHERE id = :id AND status != 'DELETING'",
    )
    suspend fun markUploaded(
        id: String,
        contentType: String?,
        size: Long,
        etag: String?,
        at: Long,
    ): Int

    /**
     * Drop a row no worker holds: waiting, or failed. Whatever an earlier
     * attempt may have left on the server isn't known to be this device's,
     * so nothing is deleted there by name (issue #56); if the bytes did
     * land, the next listing shows them, to be deleted by server id. 0 when
     * a worker has claimed it since.
     */
    @Query("DELETE FROM attachments WHERE id = :id AND status IN ('PENDING', 'FAILED')")
    suspend fun deleteUnclaimed(id: String): Int

    /**
     * Keep the [etag] the server gave an upload the user cancelled while it
     * was out: the proof that the bytes at its name are this device's, for
     * the conditional DELETE that removes them (issue #56).
     */
    @Query("UPDATE attachments SET etag = :etag WHERE id = :id AND status = 'DELETING'")
    suspend fun confirmLanded(
        id: String,
        etag: String?,
    )

    /**
     * Drop a tombstone nothing proves landed: its upload was never sent, or
     * never answered with success. A DELETE by name could remove another
     * client's file there (issue #56). 0 when there is proof.
     */
    @Query("DELETE FROM attachments WHERE id = :id AND status = 'DELETING' AND etag IS NULL")
    suspend fun deleteUnconfirmedTombstone(id: String): Int

    /**
     * Put a settled row back in the queue with a fresh budget. The user asked
     * for this one explicitly (issue #23), so it is not a continuation of the
     * attempts that failed.
     */
    @Query("UPDATE attachments SET status = 'PENDING', attempts = 0 WHERE id = :id")
    suspend fun rearmForRetry(id: String)

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
