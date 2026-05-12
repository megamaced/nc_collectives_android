package com.megamaced.nccollectives.data.mapper

import com.megamaced.nccollectives.data.api.dto.CollectiveDto
import com.megamaced.nccollectives.data.api.dto.PageDto
import com.megamaced.nccollectives.data.db.entity.CollectiveEntity
import com.megamaced.nccollectives.data.db.entity.PageEntity
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.Page

internal fun CollectiveDto.toEntity(now: Long): CollectiveEntity =
    CollectiveEntity(
        id = id,
        name = name,
        slug = slug,
        emoji = emoji,
        canEdit = canEdit,
        canShare = canShare,
        isPageShare = isPageShare,
        trashTimestamp = trashTimestamp,
        userFavoritePagesCsv = userFavoritePages.joinToString(","),
        lastSyncedAt = now,
    )

internal fun CollectiveEntity.toDomain(): Collective =
    Collective(
        id = id,
        name = name,
        slug = slug,
        emoji = emoji,
        canEdit = canEdit,
        canShare = canShare,
        isPageShare = isPageShare,
        trashed = trashTimestamp != null,
        favoritePageIds = userFavoritePagesCsv.toLongCsvSet(),
    )

internal fun PageDto.toEntity(
    collectiveId: Long,
    now: Long,
    existingBody: String?,
    existingEtag: String?,
): PageEntity =
    PageEntity(
        id = id,
        collectiveId = collectiveId,
        parentId = parentId,
        title = title,
        emoji = emoji,
        tagsCsv = tags.joinToString(TAG_SEP),
        subpageOrderCsv = subpageOrder.joinToString(","),
        isFullWidth = isFullWidth,
        trashTimestamp = trashTimestamp,
        serverTimestamp = timestamp,
        size = size,
        fileName = fileName,
        filePath = filePath,
        collectivePath = collectivePath,
        linkedPageIdsCsv = linkedPageIds.joinToString(","),
        lastUserDisplayName = lastUserDisplayName,
        bodyMd = existingBody,
        bodyEtag = existingEtag,
        lastSyncedAt = now,
    )

internal fun PageEntity.toDomain(): Page =
    Page(
        id = id,
        collectiveId = collectiveId,
        parentId = parentId,
        title = title,
        emoji = emoji,
        tags = if (tagsCsv.isEmpty()) emptyList() else tagsCsv.split(TAG_SEP),
        subpageOrder = subpageOrderCsv.toLongCsvList(),
        isFullWidth = isFullWidth,
        trashed = trashTimestamp != null,
        serverTimestamp = serverTimestamp,
        size = size,
        fileName = fileName,
        filePath = filePath,
        collectivePath = collectivePath,
        linkedPageIds = linkedPageIdsCsv.toLongCsvList(),
        lastUserDisplayName = lastUserDisplayName,
        bodyMd = bodyMd,
    )

// Tag values may contain commas, so we use the Unit Separator control
// character (U+001F) — never produced by user input.
private const val TAG_SEP = ""

private fun String.toLongCsvList(): List<Long> = if (isEmpty()) emptyList() else split(',').mapNotNull { it.trim().toLongOrNull() }

private fun String.toLongCsvSet(): Set<Long> = toLongCsvList().toSet()
