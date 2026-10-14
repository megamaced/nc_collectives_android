package com.megamaced.nccollectives.domain.model

/** A tag and how many live cached pages carry it, across every collective. */
data class TagCount(
    val name: String,
    val pageCount: Int,
)

/**
 * Pages waiting on the user or the network: [conflicts] hold a draft that
 * lost to a server change and needs resolving; [queued] hold an edit not
 * yet sent.
 */
data class PageAttention(
    val conflicts: List<PageListItem>,
    val queued: List<PageListItem>,
) {
    val isEmpty: Boolean get() = conflicts.isEmpty() && queued.isEmpty()
}
