package com.megamaced.nccollectives.domain.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Collective
import kotlinx.coroutines.flow.Flow

interface CollectiveRepository {
    /** Emits the cached list immediately, then updates as the cache changes. */
    fun observeCollectives(): Flow<List<Collective>>

    /** Force a network refresh; the resulting list lands in the Flow above. */
    suspend fun refresh(): ApiResult<Unit>

    /**
     * Add or remove [pageId] from the user's favorites for [collectiveId].
     * Applies optimistically to Room first, rolls the cache back on failure.
     */
    suspend fun toggleFavorite(
        collectiveId: Long,
        pageId: Long,
        favorite: Boolean,
    ): ApiResult<Unit>

    /**
     * Create a new collective (Batch 22). Server also creates the underlying
     * Nextcloud Team. The created [Collective] is upserted into the local
     * cache so the existing `observeCollectives` Flow surfaces it immediately.
     */
    suspend fun createCollective(
        name: String,
        emoji: String?,
    ): ApiResult<Collective>

    /**
     * Set the collective's emoji (pass an empty string to clear). The
     * Collectives API does not support renaming, so this is the only
     * mutable metadata field that callers can touch. Applies optimistically
     * to Room first; rolls back on failure.
     */
    suspend fun setCollectiveEmoji(
        collectiveId: Long,
        emoji: String,
    ): ApiResult<Unit>

    /**
     * Soft-delete a collective. Drops the local row so it disappears from
     * the list immediately. Recoverable via [restoreTrashedCollective]
     * until [permanentlyDeleteCollective] is called.
     */
    suspend fun trashCollective(collectiveId: Long): ApiResult<Unit>

    /**
     * Fetched on demand (not cached in Room) — same pattern as
     * `PageRepository.listTrashedPages`.
     */
    suspend fun listTrashedCollectives(): ApiResult<List<Collective>>

    /**
     * Restore a trashed collective. Triggers a [refresh] on success so the
     * collective reappears in the active list.
     */
    suspend fun restoreTrashedCollective(collectiveId: Long): ApiResult<Unit>

    /**
     * Permanently delete a trashed collective. Irreversible — also tears
     * down the underlying Team (server-side, `?circle=true`) and cascades
     * every locally-cached row for the collective: pages, attachments, and
     * any queued edits.
     */
    suspend fun permanentlyDeleteCollective(collectiveId: Long): ApiResult<Unit>
}
