package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Shape returned in `GET /apps/collectives/api/v1.0/collectives/{id}/pages`.
 * Collectives stores the page body as a markdown file in the user's Files
 * area at `{collectivePath}/{filePath}/{fileName}` — the body itself is not
 * returned in this JSON response and is fetched over WebDAV (see Batch 6).
 */
@Serializable
data class PageDto(
    val id: Long,
    val title: String,
    val slug: String? = null,
    val emoji: String? = null,
    val parentId: Long = 0,
    val subpageOrder: List<Long> = emptyList(),
    val isFullWidth: Boolean = false,
    val tags: List<String> = emptyList(),
    val trashTimestamp: Long? = null,
    /** Last-modified timestamp, seconds since epoch. */
    val timestamp: Long = 0,
    val size: Long = 0,
    /** Filename within the collective folder, e.g. `Readme.md` or `Title.md`. */
    val fileName: String = "",
    /** Path within the collective, no leading or trailing slash. Empty for top-level pages. */
    val filePath: String = "",
    val filePathString: String = "",
    /** Absolute path within the user's Files area, e.g. `.Collectives/Wiki`. */
    val collectivePath: String = "",
    val collectiveNameWithEmoji: String? = null,
    val shareToken: String? = null,
    val linkedPageIds: List<Long> = emptyList(),
    val lastUserId: String = "",
    val lastUserDisplayName: String = "",
)

@Serializable
data class PagesEnvelopeData(
    val pages: List<PageDto> = emptyList(),
)

/**
 * Single-page envelope returned by endpoints that operate on one page —
 * `POST /pages/{parentId}` (create), `PUT /pages/{id}` (rename/move/copy),
 * `GET /pages/{id}` (single fetch). Spec uses key `page` (singular).
 */
@Serializable
data class PageEnvelopeData(
    val page: PageDto,
)
