package com.megamaced.nccollectives.domain.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.model.SaveOutcome
import kotlinx.coroutines.flow.Flow

interface PageRepository {
    /**
     * Every non-trashed page in [collectiveId] as list rows, in title
     * order. What the tree, Favorites and every other page list observes.
     *
     * R-54: carries no markdown — see [PageListItem]. Distinct-until-
     * changed, so a body write that leaves every list-visible field alone
     * (which is every body write) stops here instead of re-flattening the
     * tree downstream.
     */
    fun observePageList(collectiveId: Long): Flow<List<PageListItem>>

    /**
     * Every non-trashed page in [collectiveId] as full [Page]s, same order
     * as [observePageList].
     *
     * R-54: for the pickers that need the detail model — "move page to…"
     * and the share sheet's target list. Both take a snapshot or live only
     * as long as a modal, which is what makes reading whole rows (bodies
     * included) affordable there. Anything that renders a standing list
     * wants [observePageList].
     */
    fun observePages(collectiveId: Long): Flow<List<Page>>

    /**
     * Most-recently-edited non-trashed pages in [collectiveId], excluding
     * the landing page. Backs the "Recent pages" strip on the page-tree
     * screen.
     */
    fun observeRecentPages(
        collectiveId: Long,
        limit: Int,
    ): Flow<List<PageListItem>>

    fun observePage(pageId: Long): Flow<Page?>

    /**
     * The collective's landing page (`parentId == 0`), or null while the
     * collective has no cached pages.
     *
     * R-56: a [Page], not a [PageListItem], because the landing card draws
     * a snippet of the body — the one place a "list" screen legitimately
     * reads markdown. It gets it from a single-row flow so the tree's list
     * flow can stay body-free.
     */
    fun observeLandingPage(collectiveId: Long): Flow<Page?>

    suspend fun refresh(collectiveId: Long): ApiResult<Unit>

    suspend fun getPage(pageId: Long): Page?

    suspend fun fetchBody(pageId: Long): ApiResult<String>

    /**
     * Revalidates the cached body against the server and updates the cache
     * if it has moved on. Returns whether the cached body actually changed.
     *
     * B-58: this is what page *content* freshness hangs off. The metadata
     * refresh deliberately preserves the cached body (a page list carries no
     * markdown), and `SyncWorker` doesn't pull bodies either — so without a
     * revalidation on open, a body fetched once stayed frozen for the life of
     * the install no matter what the server said.
     *
     * Falls back to an unconditional [fetchBody] when there's nothing cached
     * to validate against; otherwise costs a `304` when nothing changed.
     */
    suspend fun refreshBodyIfChanged(pageId: Long): ApiResult<Boolean>

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

    /**
     * Create a new tag on [collectiveId] (OCS-5, Batch 18k). [color] is
     * the 6-hex display colour without a `#` prefix. Returns the new
     * tag with its server-assigned id.
     */
    suspend fun createTag(
        collectiveId: Long,
        name: String,
        color: String,
    ): ApiResult<com.megamaced.nccollectives.domain.model.PageTag>

    /** Add or remove a single tag. Optimistic local update, rolls back on failure. */
    suspend fun togglePageTag(
        pageId: Long,
        tagId: Long,
        tagName: String,
        add: Boolean,
    ): ApiResult<Unit>

    /**
     * Rename a page within its current parent. Works for both leaf and
     * folder pages — the server handles the directory rename atomically
     * (Batch 18i, OCS-2).
     */
    suspend fun renamePage(
        pageId: Long,
        newTitle: String,
    ): ApiResult<Unit>

    /**
     * Move a page under [newParentPageId] in the same collective. Works
     * for both leaf and folder pages; the server promotes a leaf
     * destination to a folder transparently (Batch 18i, OCS-2).
     * Cross-collective moves are out of scope.
     */
    suspend fun movePage(
        pageId: Long,
        newParentPageId: Long,
    ): ApiResult<Unit>

    /**
     * Create a new page under [parentPageId]. The server handles
     * filesystem naming, indexing, and leaf-to-folder promotion of the
     * parent atomically (Batch 18h, OCS-1). If [body] is non-empty it's
     * written as the new page's markdown via WebDAV after the OCS POST
     * succeeds. Returns the resolved domain page on success.
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
     * Duplicate [pageId] in [collectiveId] via `PUT /pages/{id}` with
     * `copy=true` (Batch 23). Returns the newly-created page; the
     * collective is also refreshed so the copy appears in the tree.
     */
    suspend fun copyPage(
        collectiveId: Long,
        pageId: Long,
    ): ApiResult<Page>

    /**
     * Persist a new child ordering for [parentPageId] (Batch 23). The
     * caller passes the desired sibling-id order; we optimistically
     * write the new CSV to the cached parent row so the tree reflects
     * the order immediately, then PUT to the server and roll back on
     * failure.
     */
    suspend fun setSubpageOrder(
        collectiveId: Long,
        parentPageId: Long,
        subpageOrderIds: List<Long>,
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
     * Pages in [collectiveId] carrying [tagName] (Batch 25). Backs the
     * Browse-by-tag screen reached by tapping a tag chip on PageView or
     * TagPickerSheet. `tagsCsv` stores tag *names* not ids, so we query
     * by name; the [TagBrowse][com.megamaced.nccollectives.ui.navigation.Destination.TagBrowse]
     * route also carries the name for the same reason.
     */
    fun observePagesWithTagInCollective(
        collectiveId: Long,
        tagName: String,
    ): Flow<List<PageListItem>>

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
