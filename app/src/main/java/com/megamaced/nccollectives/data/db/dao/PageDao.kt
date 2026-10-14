package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.PageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Projection returned by [PageDao.collectiveIdsForPages] — the page-to-
 * collective mapping with none of the row's body columns. Not an entity:
 * a query projection carries no schema of its own.
 */
data class PageCollectiveRef(
    val id: Long,
    val collectiveId: Long,
)

/**
 * R-54: the columns a page *list* row is made of — everything except the
 * two potentially-huge markdown columns (`bodyMd`, `draftBodyMd`), the
 * WebDAV-only path/size fields, and the sync bookkeeping.
 *
 * `SELECT *` on `pages` drags the cached markdown body and any conflict
 * draft along with every row, so [PageDao.observeForCollective] over a
 * 200-page collective with bodies cached read on the order of megabytes
 * *per emission*. Room's invalidation is table-level, so every
 * `updateBody` — one per page open, via the B-58 revalidation — re-emitted
 * the whole tree and re-paid all of it.
 *
 * [hasDraft] is the one fact about a draft a list row could want (render a
 * marker) without carrying the text. `linkedPageIdsCsv` is deliberately
 * absent: the only consumer of linked ids is [PageDao.observeBacklinksIn],
 * which narrows on them in SQL against the full rows, so lifting them here
 * would buy a CSV-split allocation per row for nobody.
 *
 * Not an entity — a query projection carries no schema of its own, so this
 * is not a schema change and needs no migration. Room binds by *column
 * name*, so these property names and the select lists in [PageDao] have to
 * stay in step. The list is spelled out in each query rather than shared
 * through a constant so that every `@Query` stays a plain string literal.
 */
data class PageListRow(
    val id: Long,
    val collectiveId: Long,
    val parentId: Long,
    val title: String,
    val emoji: String?,
    val tagsCsv: String,
    val subpageOrderCsv: String,
    val trashTimestamp: Long?,
    val serverTimestamp: Long,
    val lastUserDisplayName: String,
    /** `draftBodyMd IS NOT NULL` — whether there's a draft, never what it says. */
    val hasDraft: Boolean,
)

/**
 * Projection returned by [PageDao.observeLastEditedByCollective]: when the
 * most recently changed cached page in each collective was saved, in
 * seconds since epoch. Not an entity.
 */
data class CollectiveLastEdited(
    val collectiveId: Long,
    val lastEditedAt: Long,
)

@Dao
interface PageDao {
    /**
     * The hot query: backs the page tree, Favorites, and everything else
     * that renders a collective's pages as a list. R-54: projected to
     * [PageListRow], which is where the reasoning lives.
     */
    @Query(
        "SELECT id, collectiveId, parentId, title, emoji, tagsCsv, subpageOrderCsv, " +
            "trashTimestamp, serverTimestamp, lastUserDisplayName, " +
            "draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM pages WHERE collectiveId = :collectiveId AND trashTimestamp IS NULL " +
            "ORDER BY title COLLATE NOCASE ASC",
    )
    fun observeForCollective(collectiveId: Long): Flow<List<PageListRow>>

    /**
     * Full rows for a collective — same filter and order as
     * [observeForCollective], every column.
     *
     * R-54: kept for the two consumers that hand these rows on as the
     * detail `Page` (the move-target picker and the share-target picker).
     * Both are one-shot or short-lived screens rather than a standing
     * observer, which is what makes the `SELECT *` affordable there.
     * Anything list-shaped wants [observeForCollective].
     */
    @Query(
        "SELECT * FROM pages WHERE collectiveId = :collectiveId AND trashTimestamp IS NULL " +
            "ORDER BY title COLLATE NOCASE ASC",
    )
    fun observeDetailForCollective(collectiveId: Long): Flow<List<PageEntity>>

    /**
     * R-56: the collective's landing page (`parentId == 0`) as a full row.
     *
     * The landing card renders a snippet of the page's markdown, making it
     * the one list-screen consumer that legitimately needs a body. Serving
     * it from here costs one body per emission; serving it by widening
     * [observeForCollective]'s projection would cost every row's body on
     * every emission, to draw two lines on one card.
     */
    @Query(
        "SELECT * FROM pages WHERE collectiveId = :collectiveId AND trashTimestamp IS NULL " +
            "AND parentId = 0 ORDER BY title COLLATE NOCASE ASC LIMIT 1",
    )
    fun observeLandingPage(collectiveId: Long): Flow<PageEntity?>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getById(id: Long): PageEntity?

    /**
     * R-27: bulk read of every cached row for a collective. Used by
     * `refresh()` to look up existing body / etag / draft in one query
     * instead of `getById(dto.id)` N times.
     */
    @Query("SELECT * FROM pages WHERE collectiveId = :collectiveId")
    suspend fun listForCollective(collectiveId: Long): List<PageEntity>

    /**
     * R-46: which collective each of [ids] belongs to, in one query.
     * Search-hit mapping needs nothing but that one column, and [getById]
     * is a `SELECT *` that drags the whole cached markdown body along —
     * a 20-result search meant 20 body-sized reads to fetch 20 Longs.
     *
     * Callers must skip the call on an empty [ids]: Room expands the
     * binding to `IN ()`, which is a SQLite syntax error (same trap as
     * B-42 in `AttachmentRepositoryImpl`).
     */
    @Query("SELECT id, collectiveId FROM pages WHERE id IN (:ids)")
    suspend fun collectiveIdsForPages(ids: List<Long>): List<PageCollectiveRef>

    @Query("SELECT * FROM pages WHERE id = :id")
    fun observeById(id: Long): Flow<PageEntity?>

    @Query(
        "SELECT id FROM pages WHERE collectiveId = :collectiveId " +
            "AND title = :title COLLATE NOCASE AND trashTimestamp IS NULL LIMIT 1",
    )
    suspend fun findIdByTitleInCollective(
        collectiveId: Long,
        title: String,
    ): Long?

    /** R-54: recent-pages strip, projected — see [PageListRow]. */
    @Query(
        "SELECT id, collectiveId, parentId, title, emoji, tagsCsv, subpageOrderCsv, " +
            "trashTimestamp, serverTimestamp, lastUserDisplayName, " +
            "draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM pages WHERE collectiveId = :collectiveId AND trashTimestamp IS NULL " +
            "AND parentId != 0 ORDER BY serverTimestamp DESC LIMIT :limit",
    )
    fun observeRecentInCollective(
        collectiveId: Long,
        limit: Int,
    ): Flow<List<PageListRow>>

    /**
     * The recent-pages strip on the collective list: the most recently
     * changed pages across every collective, landing pages excluded as in
     * [observeRecentInCollective]. Pages of a trashed collective stay out,
     * since the list they would open from doesn't show it.
     *
     * R-54: projected — see [PageListRow]. Served by
     * `index_pages_serverTimestamp` (v11), which lets SQLite walk rows newest
     * first and stop at [limit] instead of sorting the whole table.
     *
     * The collective check is a correlated `EXISTS`, not `collectiveId IN
     * (SELECT …)`, and that is what keeps the index in play: an `IN` list
     * gives the planner an indexable term on `pages.collectiveId`, which it
     * takes — and then sorts every matching row into a temp B-tree, reading
     * each one's `draftBodyMd`. `EXPLAIN QUERY PLAN` against the v11 schema
     * shows `SCAN pages USING INDEX index_pages_serverTimestamp` for this
     * shape and `USE TEMP B-TREE FOR ORDER BY` for the `IN` one.
     */
    @Query(
        "SELECT id, collectiveId, parentId, title, emoji, tagsCsv, subpageOrderCsv, " +
            "trashTimestamp, serverTimestamp, lastUserDisplayName, " +
            "draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM pages WHERE trashTimestamp IS NULL AND parentId != 0 " +
            "AND EXISTS (SELECT 1 FROM collectives WHERE collectives.id = pages.collectiveId " +
            "AND collectives.trashTimestamp IS NULL) " +
            "ORDER BY serverTimestamp DESC LIMIT :limit",
    )
    fun observeRecentAcrossCollectives(limit: Int): Flow<List<PageListRow>>

    /**
     * Home's "Jump back in": pages this device has shown, most recent first.
     * Landing pages count, since the user opened them. R-54: projected — see
     * [PageListRow].
     */
    @Query(
        "SELECT p.id, p.collectiveId, p.parentId, p.title, p.emoji, p.tagsCsv, p.subpageOrderCsv, " +
            "p.trashTimestamp, p.serverTimestamp, p.lastUserDisplayName, " +
            "p.draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM page_views v JOIN pages p ON p.id = v.pageId " +
            "WHERE p.trashTimestamp IS NULL " +
            "AND EXISTS (SELECT 1 FROM collectives WHERE collectives.id = p.collectiveId " +
            "AND collectives.trashTimestamp IS NULL) " +
            "ORDER BY v.lastViewedAt DESC LIMIT :limit",
    )
    fun observeRecentlyViewed(limit: Int): Flow<List<PageListRow>>

    /**
     * Home's "Updated since you read it": pages the user has been shown
     * whose server version has moved on since, changed by someone else.
     *
     * `seenTimestamp = 0` means no shown version had a known timestamp, so
     * there is nothing to compare against. An unknown last editor ('') is
     * left out too: it may well be the user.
     */
    @Query(
        "SELECT p.id, p.collectiveId, p.parentId, p.title, p.emoji, p.tagsCsv, p.subpageOrderCsv, " +
            "p.trashTimestamp, p.serverTimestamp, p.lastUserDisplayName, " +
            "p.draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM page_views v JOIN pages p ON p.id = v.pageId " +
            "WHERE v.seenTimestamp > 0 AND p.serverTimestamp > v.seenTimestamp " +
            "AND p.lastUserId != '' AND p.lastUserId != :userId " +
            "AND p.trashTimestamp IS NULL " +
            "AND EXISTS (SELECT 1 FROM collectives WHERE collectives.id = p.collectiveId " +
            "AND collectives.trashTimestamp IS NULL) " +
            "ORDER BY p.serverTimestamp DESC LIMIT :limit",
    )
    fun observeUpdatedSinceViewed(
        userId: String,
        limit: Int,
    ): Flow<List<PageListRow>>

    /**
     * Home's "Your recent edits": pages whose last editor is [userId]. The
     * server reports only the last editor, so a page the user edited and
     * someone else edited after drops out. R-54: projected.
     */
    @Query(
        "SELECT p.id, p.collectiveId, p.parentId, p.title, p.emoji, p.tagsCsv, p.subpageOrderCsv, " +
            "p.trashTimestamp, p.serverTimestamp, p.lastUserDisplayName, " +
            "p.draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM pages p WHERE p.lastUserId = :userId AND p.trashTimestamp IS NULL " +
            "AND EXISTS (SELECT 1 FROM collectives WHERE collectives.id = p.collectiveId " +
            "AND collectives.trashTimestamp IS NULL) " +
            "ORDER BY p.serverTimestamp DESC LIMIT :limit",
    )
    fun observeLastEditedBy(
        userId: String,
        limit: Int,
    ): Flow<List<PageListRow>>

    /**
     * Pages holding a conflict draft: a save of the user's lost to a
     * server change, kept on the page for them to resolve. R-54: projected;
     * the draft itself is never read here.
     */
    @Query(
        "SELECT p.id, p.collectiveId, p.parentId, p.title, p.emoji, p.tagsCsv, p.subpageOrderCsv, " +
            "p.trashTimestamp, p.serverTimestamp, p.lastUserDisplayName, " +
            "p.draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM pages p WHERE p.draftBodyMd IS NOT NULL AND p.trashTimestamp IS NULL " +
            "AND EXISTS (SELECT 1 FROM collectives WHERE collectives.id = p.collectiveId " +
            "AND collectives.trashTimestamp IS NULL) " +
            "ORDER BY p.title COLLATE NOCASE ASC",
    )
    fun observeWithDrafts(): Flow<List<PageListRow>>

    /**
     * Pages with an edit queued for the server and not yet sent: offline
     * saves, and any in flight. Conflicted entries are left out — those
     * pages carry a draft, see [observeWithDrafts]. Oldest first.
     */
    @Query(
        "SELECT p.id, p.collectiveId, p.parentId, p.title, p.emoji, p.tagsCsv, p.subpageOrderCsv, " +
            "p.trashTimestamp, p.serverTimestamp, p.lastUserDisplayName, " +
            "p.draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM edit_queue e JOIN pages p ON p.id = e.pageId " +
            "WHERE e.status != 'CONFLICTED' AND p.trashTimestamp IS NULL " +
            "AND EXISTS (SELECT 1 FROM collectives WHERE collectives.id = p.collectiveId " +
            "AND collectives.trashTimestamp IS NULL) " +
            "ORDER BY e.queuedAt ASC",
    )
    fun observeWithQueuedEdits(): Flow<List<PageListRow>>

    /**
     * Every non-empty tag list across the live cache, for counting tags on
     * Home. The CSV is split in Kotlin; SQLite has no split.
     */
    @Query(
        "SELECT p.tagsCsv FROM pages p WHERE p.tagsCsv != '' AND p.trashTimestamp IS NULL " +
            "AND EXISTS (SELECT 1 FROM collectives WHERE collectives.id = p.collectiveId " +
            "AND collectives.trashTimestamp IS NULL)",
    )
    fun observeTagsCsv(): Flow<List<String>>

    /**
     * [observePagesWithTagInCollective] across every live collective, for
     * Browse-by-tag opened from Home. Same pattern contract.
     */
    @Query(
        "SELECT p.id, p.collectiveId, p.parentId, p.title, p.emoji, p.tagsCsv, p.subpageOrderCsv, " +
            "p.trashTimestamp, p.serverTimestamp, p.lastUserDisplayName, " +
            "p.draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM pages p WHERE p.trashTimestamp IS NULL " +
            "AND (:sep || p.tagsCsv || :sep) LIKE :likePattern ESCAPE '\\' " +
            "AND EXISTS (SELECT 1 FROM collectives WHERE collectives.id = p.collectiveId " +
            "AND collectives.trashTimestamp IS NULL) " +
            "ORDER BY p.title COLLATE NOCASE ASC",
    )
    fun observePagesWithTag(
        sep: String,
        likePattern: String,
    ): Flow<List<PageListRow>>

    /**
     * The direct children of [parentId], for the page screen's subpage
     * list. Unordered: sibling order is the collective's page-order setting,
     * which is applied in Kotlin alongside the tree's (`orderSiblings`).
     * [collectiveId] is only there to lead the `(collectiveId, parentId)`
     * index.
     *
     * R-54: projected — see [PageListRow].
     */
    @Query(
        "SELECT id, collectiveId, parentId, title, emoji, tagsCsv, subpageOrderCsv, " +
            "trashTimestamp, serverTimestamp, lastUserDisplayName, " +
            "draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM pages WHERE collectiveId = :collectiveId AND parentId = :parentId " +
            "AND trashTimestamp IS NULL",
    )
    fun observeChildren(
        collectiveId: Long,
        parentId: Long,
    ): Flow<List<PageListRow>>

    /**
     * Newest page timestamp per collective, for sorting the collective list
     * by "Last edited". Collectives with no cached pages have no row.
     */
    @Query(
        "SELECT collectiveId, MAX(serverTimestamp) AS lastEditedAt FROM pages " +
            "WHERE trashTimestamp IS NULL GROUP BY collectiveId",
    )
    fun observeLastEditedByCollective(): Flow<List<CollectiveLastEdited>>

    /**
     * Pages in [collectiveId] whose `tagsCsv` contains [tagName] (Batch 25).
     * `tagsCsv` stores tag *names* separated by U+001F; wrapping the column
     * with the separator on both sides lets a single LIKE pattern match
     * regardless of whether the tag sits first/middle/last/alone. The
     * pattern + sep wrapping are passed in from the repository so the SQL
     * stays opaque to the choice of separator character.
     *
     * R-54: projected — Browse-by-tag renders the same row shape the tree
     * does, and `tagsCsv` is in the projection so the repository's
     * exact-match post-filter still works.
     */
    @Query(
        "SELECT id, collectiveId, parentId, title, emoji, tagsCsv, subpageOrderCsv, " +
            "trashTimestamp, serverTimestamp, lastUserDisplayName, " +
            "draftBodyMd IS NOT NULL AS hasDraft " +
            "FROM pages WHERE collectiveId = :collectiveId AND trashTimestamp IS NULL " +
            "AND (:sep || tagsCsv || :sep) LIKE :likePattern ESCAPE '\\' " +
            "ORDER BY title COLLATE NOCASE ASC",
    )
    fun observePagesWithTagInCollective(
        collectiveId: Long,
        sep: String,
        likePattern: String,
    ): Flow<List<PageListRow>>

    /**
     * R-57: rows in [collectiveId] whose `linkedPageIdsCsv` mentions a
     * page, narrowed in SQL by the same separator-wrapped LIKE trick as
     * [observePagesWithTagInCollective].
     *
     * Backlinks are a page-screen observer, live for as long as a page is
     * open — which is exactly when `updateBody` fires. Deriving them from
     * a whole-collective `SELECT *` therefore re-read every cached body in
     * the collective on the open of the page whose body had just landed.
     * Narrowing to the handful of rows that actually link means only those
     * rows' bodies are ever materialised.
     *
     * No `ESCAPE`: the caller builds the pattern from a page id, so it is
     * digits and separators only and can't carry a LIKE wildcard. The
     * repository still applies an exact-match filter over the parsed ids,
     * so this only ever has to narrow, never decide.
     */
    @Query(
        "SELECT * FROM pages WHERE collectiveId = :collectiveId AND trashTimestamp IS NULL " +
            "AND (:sep || linkedPageIdsCsv || :sep) LIKE :likePattern " +
            "ORDER BY title COLLATE NOCASE ASC",
    )
    fun observeBacklinksIn(
        collectiveId: Long,
        sep: String,
        likePattern: String,
    ): Flow<List<PageEntity>>

    @Upsert
    suspend fun upsertAll(pages: List<PageEntity>)

    /**
     * Store a body. [bodyTimestamp] is the server mtime of exactly this
     * version: the fetch's `Last-Modified` for a body from the server, null
     * for the user's own save — see [PageEntity.bodyTimestamp].
     */
    @Query(
        "UPDATE pages SET bodyMd = :body, bodyEtag = :etag, bodyTimestamp = :bodyTimestamp, " +
            "lastSyncedAt = :syncedAt WHERE id = :id",
    )
    suspend fun updateBody(
        id: Long,
        body: String,
        etag: String?,
        bodyTimestamp: Long?,
        syncedAt: Long,
    )

    @Query("SELECT EXISTS(SELECT 1 FROM pages WHERE id = :id)")
    suspend fun exists(id: Long): Boolean

    @Query("UPDATE pages SET draftBodyMd = :draft WHERE id = :id")
    suspend fun updateDraft(
        id: Long,
        draft: String?,
    )

    @Query("UPDATE pages SET emoji = :emoji WHERE id = :id")
    suspend fun updateEmoji(
        id: Long,
        emoji: String?,
    )

    @Query("UPDATE pages SET tagsCsv = :csv WHERE id = :id")
    suspend fun updateTagsCsv(
        id: Long,
        csv: String,
    )

    @Query("UPDATE pages SET subpageOrderCsv = :csv WHERE id = :id")
    suspend fun updateSubpageOrderCsv(
        id: Long,
        csv: String,
    )

    @Query("DELETE FROM pages WHERE collectiveId = :collectiveId AND id NOT IN (:keepIds)")
    suspend fun deleteMissingForCollective(
        collectiveId: Long,
        keepIds: List<Long>,
    )

    @Query("SELECT id FROM pages WHERE collectiveId = :collectiveId")
    suspend fun idsForCollective(collectiveId: Long): List<Long>

    @Query("DELETE FROM pages WHERE collectiveId = :collectiveId")
    suspend fun deleteForCollective(collectiveId: Long)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pages")
    suspend fun clear()
}
