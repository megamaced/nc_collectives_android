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
    indices = [
        Index("pageId"),
        // R-11: covers `AttachmentDao.pendingUploads()` which filters
        // `status IN ('PENDING', 'UPLOADING')` on every worker wakeup.
        // Added in DB v6 / Batch 18m.
        Index("status"),
    ],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val pageId: Long,
    val fileName: String,
    val contentType: String?,
    val size: Long,
    val lastModifiedMs: Long,
    /**
     * The server's ETag for this row's file, when this device knows it. On a
     * tombstone it is the proof that the bytes at its name are this device's
     * upload: set only when an upload the user had cancelled was answered
     * with success, and sent as `If-Match` on the DELETE that removes it.
     * A tombstone without one is dropped locally; its upload may have been
     * refused because the name was taken, and whatever is there may be
     * another client's (issue #56).
     */
    val etag: String?,
    /** "REMOTE", "PENDING", "UPLOADING", "FAILED", "DELETING". */
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
    /**
     * Attempts `AttachmentUploadWorker` has spent on *this row*, incremented
     * as each one begins. Issue #30 — see `EditQueueEntity.attempts` for why
     * `runAttemptCount` could not answer this.
     *
     * Reset to zero when the user asks for a retry, and when a remote
     * collision moves the upload to a new filename.
     */
    val attempts: Int = 0,
) {
    companion object {
        const val STATUS_REMOTE = "REMOTE"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_UPLOADING = "UPLOADING"
        const val STATUS_FAILED = "FAILED"

        /**
         * Tombstone: the user deleted this attachment while its upload was
         * in flight (issue #35).
         *
         * A local-only delete could not honour the request — removing the
         * Room row neither cancels an in-flight PUT nor undoes one that
         * has landed, so the file stayed on the server and the next
         * listing inserted it again as `REMOTE`, visibly resurrecting an
         * attachment the UI had reported deleted. The row is kept instead,
         * hidden from `observeForPage`, so the worker doesn't record the
         * upload. If the upload is answered with success, the worker keeps
         * its [etag] on the tombstone and removes the file with a WebDAV
         * DELETE conditional on it — durable, so it survives process death
         * before the delete goes out. Any other answer, or none, leaves
         * nothing proving the bytes at that name are this device's, and the
         * tombstone is dropped locally (issue #56).
         */
        const val STATUS_DELETING = "DELETING"

        fun key(
            pageId: Long,
            fileName: String,
        ): String = "$pageId/$fileName"
    }
}
