package com.megamaced.nccollectives.domain.model

data class SearchHit(
    val title: String,
    val snippet: String?,
    /** Resolved page id when we could derive one from the result; null otherwise. */
    val pageId: Long?,
    /** Resolved collective id (matched via local cache) when known; null otherwise. */
    val collectiveId: Long?,
)
