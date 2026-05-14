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

    /**
     * Create a new leaf page under [parentPageId] (must be a folder page
     * — i.e. its file is `Readme.md`). PUTs the body over WebDAV and then
     * refreshes the page list so the new id appears locally. Returns the
     * resolved domain [com.megamaced.nccollectives.domain.model.Page] on
     * success.
     *
     * Promotion of a leaf parent into a folder isn't supported yet —
     * callers must pick an existing folder.
     */
    suspend fun createPage(
        collectiveId: Long,
        parentPageId: Long,
        title: String,
        body: String,
    ): ApiResult<com.megamaced.nccollectives.domain.model.Page>

    /**
     * Append [text] to a page's markdown body. Uses the regular save path
     * (with offline queueing). If the cached body is null, it's fetched
     * first.
     */
    suspend fun appendToPage(
        pageId: Long,
        text: String,
    ): com.megamaced.nccollectives.domain.model.SaveOutcome

    /**
     * Soft-delete a page. Refuses the landing page (parentId == 0); rename
     * the collective instead. On success the local row is dropped from the
     * active list so observers reflect the change immediately.
     */
    suspend fun trashPage(pageId: Long): ApiResult<Unit>

    /**
     * Fetch the per-collective trash. Trashed pages aren't cached in Room
     * (they don't show up in the regular listing) so this returns a
     * snapshot list rather than a Flow.
     */
    suspend fun listTrashedPages(collectiveId: Long): ApiResult<List<com.megamaced.nccollectives.domain.model.Page>>

    /** Restore a trashed page; triggers a `refresh(collectiveId)` on success. */
    suspend fun restorePage(
        collectiveId: Long,
        pageId: Long,
    ): ApiResult<Unit>

    /** Permanently delete a trashed page. Irreversible. */
    suspend fun purgePage(
        collectiveId: Long,
        pageId: Long,
    ): ApiResult<Unit>

    /**
     * Pages whose `linkedPageIds` contain [pageId]. Backlinks live in the
     * same collective by design — Collectives' indexer only tracks
     * intra-collective references.
     */
    fun observeBacklinksFor(
        collectiveId: Long,
        pageId: Long,
    ): Flow<List<Page>>

    /**
     * Resolve a wikilink target (`[[Page Name]]`, `./Page%20Name`, etc.) to a
     * cached page id within [collectiveId]. Matches title case-insensitively
     * and strips a trailing `.md` extension.
     */
    suspend fun resolvePageByTitle(
        collectiveId: Long,
        title: String,
    ): Long?
}
