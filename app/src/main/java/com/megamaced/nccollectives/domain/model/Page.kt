package com.megamaced.nccollectives.domain.model

/**
 * A page with its content — the model the page screens, the editors and the
 * WebDAV paths hang off.
 *
 * R-54: deliberately *not* the model a list renders. [bodyMd] and
 * [draftBodyMd] can each run to megabytes, and [size] / [fileName] /
 * [filePath] / [collectivePath] are WebDAV plumbing no row draws — which
 * is the whole of what a page-list flow used to carry for every row of
 * every emission. Lists get [PageListItem] instead, which has no body
 * fields at all, so a list screen cannot quietly start reading a body
 * again and pull the cost back in.
 */
data class Page(
    val id: Long,
    val collectiveId: Long,
    val parentId: Long,
    val title: String,
    val emoji: String?,
    val tags: List<String>,
    val subpageOrder: List<Long>,
    val isFullWidth: Boolean,
    val trashed: Boolean,
    /** Last-modified timestamp from the server, seconds since epoch. */
    val serverTimestamp: Long,
    val size: Long,
    val fileName: String,
    /** Path within the collective, no leading or trailing slash. */
    val filePath: String,
    /** Absolute path within the user's Files area, e.g. `.Collectives/Wiki`. */
    val collectivePath: String,
    val linkedPageIds: List<Long>,
    val lastUserDisplayName: String,
    /** Cached markdown body once first fetched. Null until viewed. */
    val bodyMd: String?,
    /** Local draft that lost an etag race; null when no draft exists. */
    val draftBodyMd: String?,
)

/**
 * A page as a *list row*: the tree, Favorites, Browse-by-tag and the
 * recent-pages strip. Mapped straight from the `PageListRow` projection,
 * so what a list observer reads out of SQLite is exactly this and nothing
 * more (R-54).
 *
 * What's missing is the point:
 *  - no `bodyMd` / `draftBodyMd`, so a list can't carry markdown. The one
 *    list-screen consumer that legitimately renders body text — the
 *    landing card — takes a whole [Page] from its own single-row flow
 *    (R-56) rather than widening this.
 *  - no `linkedPageIds`: backlinks are filtered in SQL now (R-57), and
 *    parsing a CSV of ids per row was one of the three list allocations
 *    this split set out to remove.
 *  - no `fileName` / `filePath` / `collectivePath` / `size` — WebDAV
 *    plumbing, and nothing a row draws.
 *
 * [tags] and [subpageOrder] survive because the tag post-filter and the
 * tree's sibling ordering genuinely need them; both parse to a shared
 * `emptyList()` for the common row that has neither.
 */
data class PageListItem(
    val id: Long,
    val collectiveId: Long,
    val parentId: Long,
    val title: String,
    val emoji: String?,
    val tags: List<String>,
    val subpageOrder: List<Long>,
    val trashed: Boolean,
    /** Last-modified timestamp from the server, seconds since epoch. */
    val serverTimestamp: Long,
    val lastUserDisplayName: String,
    /** Whether the row has an unresolved local draft — never the text. */
    val hasDraft: Boolean,
)

/**
 * The result of creating a page: the page itself, and separately what
 * happened to the markdown that was meant to go in it.
 *
 * Two outcomes rather than one because they fail independently and the caller
 * needs both. The page existing is what a retry must not duplicate (issue
 * #25); the body having landed, been queued or failed outright is what the
 * user has to be told (issue #31).
 */
data class PageCreation(
    val page: Page,
    val bodyOutcome: SaveOutcome,
)
