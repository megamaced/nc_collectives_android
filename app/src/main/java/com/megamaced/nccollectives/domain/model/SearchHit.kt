package com.megamaced.nccollectives.domain.model

data class SearchHit(
    val title: String,
    val snippet: String?,
    /** Resolved page id when we could derive one from the result; null otherwise. */
    val pageId: Long?,
    /** Resolved collective id (matched via local cache) when known; null otherwise. */
    val collectiveId: Long?,
) {
    /**
     * Whether tapping the result can open it. The page screen reads the page
     * from this device's cache, and [collectiveId] is only known for a page
     * that is cached, so a hit with an id but no collective would open onto
     * "That isn't on this device yet" rather than the page.
     */
    val isOpenable: Boolean get() = pageId != null && collectiveId != null
}
