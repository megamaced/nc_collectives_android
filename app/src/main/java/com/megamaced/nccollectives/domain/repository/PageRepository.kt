package com.megamaced.nccollectives.domain.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.SaveOutcome
import kotlinx.coroutines.flow.Flow

interface PageRepository {
    fun observePages(collectiveId: Long): Flow<List<Page>>

    fun observePage(pageId: Long): Flow<Page?>

    suspend fun refresh(collectiveId: Long): ApiResult<Unit>

    suspend fun getPage(pageId: Long): Page?

    suspend fun fetchBody(pageId: Long): ApiResult<String>

    /**
     * Tries to save [newBody] to the server now. On network failure the edit
     * is enqueued for `EditFlushWorker` to retry; on 412 the user's body is
     * stored as a draft on the page row and `Conflict` is returned.
     */
    suspend fun saveBody(
        pageId: Long,
        newBody: String,
    ): SaveOutcome

    /**
     * Force-saves [newBody], bypassing the cached etag — used by the
     * "Replace page with my draft" action on the conflict banner.
     */
    suspend fun replaceWithDraft(
        pageId: Long,
        newBody: String,
    ): SaveOutcome

    /** Clears a page's local draft without changing the server. */
    suspend fun discardDraft(pageId: Long)

    /** Set or clear a page's emoji. Empty string clears. Optimistic. */
    suspend fun setEmoji(
        pageId: Long,
        emoji: String,
    ): ApiResult<Unit>

    /** List the tags defined in [collectiveId]. */
    suspend fun listTagsForCollective(collectiveId: Long): ApiResult<List<com.megamaced.nccollectives.domain.model.PageTag>>

    /** Add or remove a single tag. Optimistic local update, rolls back on failure. */
    suspend fun togglePageTag(
        pageId: Long,
        tagId: Long,
        tagName: String,
        add: Boolean,
    ): ApiResult<Unit>

    /**
     * Rename a leaf page within its current parent. Folder pages (those with
     * children) return [ApiResult.Unexpected] with a descriptive message —
     * full folder rename isn't supported yet.
     */
    suspend fun renamePage(
        pageId: Long,
        newTitle: String,
    ): ApiResult<Unit>

    /**
     * Move a leaf page under [newParentPageId] in the same collective.
     * Folder pages and cross-collective moves aren't supported yet.
     */
    suspend fun movePage(
        pageId: Long,
        newParentPageId: Long,
    ): ApiResult<Unit>
}
