package com.megamaced.nccollectives.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A pending markdown-body save. Rows are enqueued when a save can't make it
 * through to the server immediately (offline, or earlier failure) and drained
 * by `EditFlushWorker` once the network returns.
 *
 * `baseEtag` is the ETag the user saw when they started editing — sent as
 * `If-Match` on the eventual PUT so the worker can detect server-side changes
 * that happened while the edit was queued. When [forceWrite] is true the
 * worker bypasses the conflict check and writes unconditionally — used by
 * the "Replace with my draft" path (B-38).
 *
 * B-41: `pageId` is the primary key. There can only ever be one queued edit
 * per page (the save path coalesces); the previous `(autoGenerate id) +
 * unique index on pageId` combination meant `@Upsert` resolved conflicts on
 * the PK only, so an `id=0` insert for an existing `pageId` failed the
 * unique-index check before reaching the REPLACE branch.
 */
@Entity(
    tableName = "edit_queue",
    indices = [Index(value = ["status", "queuedAt"])],
)
data class EditQueueEntity(
    @PrimaryKey val pageId: Long,
    val baseEtag: String?,
    val newBodyMd: String,
    val queuedAt: Long,
    /** One of "PENDING", "IN_FLIGHT", "CONFLICTED". */
    val status: String,
    /**
     * B-46: when true, `EditFlushWorker` skips the etag-based conflict check
     * and writes unconditionally. Set by `replaceWithDraft` so the user's
     * explicit "Replace with my draft" intent isn't second-guessed by the
     * worker on a 412.
     */
    val forceWrite: Boolean = false,
)
