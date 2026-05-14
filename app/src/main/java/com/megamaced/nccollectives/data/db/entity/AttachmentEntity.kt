package com.megamaced.nccollectives.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One file inside a page's `.attachments.<pageId>` WebDAV directory.
 *
 * Doubles as the upload queue: rows with `status = PENDING` or `UPLOADING`
 * carry a `localUriString` that `AttachmentUploadWorker` reads from. Once
 * the upload lands the row is rewritten as `status = REMOTE` and the local
 * URI is cleared. `FAILED` rows are kept so the UI can surface a retry.
 *
 * `id` is `<pageId>/<fileName>` so the same filename collides across pages
 * — keeping the row stable across PROPFIND refreshes.
 */
@Entity(
    tableName = "attachments",
    indices = [Index("pageId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val pageId: Long,
    val fileName: String,
    val contentType: String?,
    val size: Long,
    val lastModifiedMs: Long,
    val etag: String?,
    /** "REMOTE", "PENDING", "UPLOADING", "FAILED". */
    val status: String,
    /** content:// URI to read bytes from for pending uploads. Null once REMOTE. */
    val localUriString: String?,
    val lastSyncedAt: Long,
    /**
     * Server-assigned id from the OCS attachments endpoint (Batch 18j).
     * Null for rows that haven't been listed yet — pending uploads and
     * pre-OCS-migration cached rows. Populated by `refresh()` after a
     * successful list; used by [deleteAttachment] for stable deletion.
     */
    val serverAttachmentId: Long? = null,
) {
    companion object {
        const val STATUS_REMOTE = "REMOTE"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_UPLOADING = "UPLOADING"
        const val STATUS_FAILED = "FAILED"

        fun key(
            pageId: Long,
            fileName: String,
        ): String = "$pageId/$fileName"
    }
}
