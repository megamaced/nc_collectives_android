package com.megamaced.nccollectives.ui.screen.home

import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.model.PageOrder

/** Test builders shared by the Home tests. */
internal fun listItem(
    id: Long,
    collectiveId: Long = 1,
    parentId: Long = 10,
    title: String = "Page $id",
    serverTimestamp: Long = 0,
    lastUserDisplayName: String = "",
    tags: List<String> = emptyList(),
) = PageListItem(
    id = id,
    collectiveId = collectiveId,
    parentId = parentId,
    title = title,
    emoji = null,
    tags = tags,
    subpageOrder = emptyList(),
    trashed = false,
    serverTimestamp = serverTimestamp,
    lastUserDisplayName = lastUserDisplayName,
    hasDraft = false,
)

internal fun collective(
    id: Long,
    name: String = "Collective $id",
    emoji: String? = null,
    canEdit: Boolean = true,
    favoritePageIds: Set<Long> = emptySet(),
) = Collective(
    id = id,
    name = name,
    slug = null,
    emoji = emoji,
    circleId = null,
    canEdit = canEdit,
    canShare = true,
    level = 0,
    userShowMembers = true,
    pageOrder = PageOrder.Custom,
    isPageShare = false,
    trashed = false,
    favoritePageIds = favoritePageIds,
)
