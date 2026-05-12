package com.megamaced.nccollectives.domain.model

data class Collective(
    val id: Long,
    val name: String,
    val slug: String?,
    val emoji: String?,
    val canEdit: Boolean,
    val canShare: Boolean,
    val isPageShare: Boolean,
    val trashed: Boolean,
    val favoritePageIds: Set<Long>,
)
