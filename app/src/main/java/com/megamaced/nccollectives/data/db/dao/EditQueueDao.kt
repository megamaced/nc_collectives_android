package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import kotlinx.coroutines.flow.Flow

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

    /**
     * The markdown of an unresolved queued edit, or null when there isn't
     * one.
     *
     * A queued edit *is* the local truth for a page's body. The page row
     * deliberately keeps holding the server's markdown and ETag, because
     * `If-Match` and conflict resolution have to compare against them — so
     * every reader that renders or extends a body has to overlay this on
     * top of `pages.bodyMd`. Issue #18: without the overlay a save that
     * only reached the queue was invisible. The page view kept rendering
     * the pre-edit text, and the next edit therefore started from that text
     * and `@Upsert` replaced the queued row, discarding the first edit with
     * no error.
     *
     * `CONFLICTED` rows are excluded. Their text is already on the page row
     * as `draftBodyMd`, where `ConflictBanner` offers it beside the server
     * version; overlaying it as the body would hide the very thing the user
     * is being asked to compare it against.
     */
    @Query("SELECT newBodyMd FROM edit_queue WHERE pageId = :pageId AND status != 'CONFLICTED' LIMIT 1")
    fun observePendingBody(pageId: Long): Flow<String?>

    /**
     * As [observePendingBody], for the one-shot reads. Kept as a separate
     * query rather than `forPage(...)?.takeIf { ... }` so the `CONFLICTED`
     * exclusion is stated once, in SQL, and can't drift between the
     * observing and one-shot paths.
     */
    @Query("SELECT newBodyMd FROM edit_queue WHERE pageId = :pageId AND status != 'CONFLICTED' LIMIT 1")
    suspend fun pendingBody(pageId: Long): String?

    /**
     * Claim a row for an attempt: `IN_FLIGHT`, and one more attempt spent.
     *
     * Issue #30: the count has to live on the row, because the worker's own
     * `runAttemptCount` belongs to the WorkRequest and a row can join the
     * database under an old one that is already deep in backoff.
     */
    @Query("UPDATE edit_queue SET status = 'IN_FLIGHT', attempts = attempts + 1 WHERE pageId = :pageId")
    suspend fun markInFlight(pageId: Long)

    /**
     * Move a queued edit onto a new page id — issue #39, where a rename or
     * move made Nextcloud reissue the page's file id and the row would
     * otherwise have been cascaded away as belonging to a page that no longer
     * exists.
     *
     * `pageId` is the primary key (B-41), so this is the whole rekey.
     */
    @Query("UPDATE edit_queue SET pageId = :newPageId WHERE pageId = :oldPageId")
    suspend fun repointPage(
        oldPageId: Long,
        newPageId: Long,
    )

    @Query("UPDATE edit_queue SET status = :status WHERE pageId = :pageId")
    suspend fun setStatus(
        pageId: Long,
        status: String,
    )

    @Query("DELETE FROM edit_queue WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: Long)

    @Query("DELETE FROM edit_queue WHERE pageId IN (:pageIds)")
    suspend fun deleteForPageIds(pageIds: List<Long>)

    /**
     * Every queued row, conflicted ones included. Used before an account
     * switch to tell the user how many local writes the wipe will discard —
     * a conflicted row is just as unresolved as a pending one.
     */
    @Query("SELECT COUNT(*) FROM edit_queue")
    suspend fun countAll(): Int

    @Query("DELETE FROM edit_queue")
    suspend fun clear()
}
