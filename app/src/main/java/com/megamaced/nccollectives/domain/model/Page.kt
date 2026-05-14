package com.megamaced.nccollectives.domain.model

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
 * Folder pages in Collectives are stored as `Readme.md` inside a directory;
 * leaf pages are stored as `<title>.md` siblings. A page can hold children
 * iff it is a folder page (or the collective's landing page, which is the
 * implicit folder at the collective root with `parentId == 0`).
 */
fun Page.isFolderPage(): Boolean = fileName.equals("Readme.md", ignoreCase = true)

fun Page.canHoldChildren(): Boolean = parentId == 0L || isFolderPage()
