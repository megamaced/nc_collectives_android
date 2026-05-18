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
