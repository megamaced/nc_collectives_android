package com.megamaced.nccollectives.data.mapper

import com.megamaced.nccollectives.data.api.dto.CollectiveDto
import com.megamaced.nccollectives.data.api.dto.PageDto
import com.megamaced.nccollectives.data.db.entity.CollectiveEntity
import com.megamaced.nccollectives.data.db.entity.PageEntity
import com.megamaced.nccollectives.data.joinTags
import com.megamaced.nccollectives.data.splitTags
import com.megamaced.nccollectives.data.toLongCsv
import com.megamaced.nccollectives.data.toLongCsvList
import com.megamaced.nccollectives.data.toLongCsvSet
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
        userFavoritePagesCsv = userFavoritePages.toLongCsv(),
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
    existingDraft: String?,
): PageEntity =
    PageEntity(
        id = id,
        collectiveId = collectiveId,
        parentId = parentId,
        title = title,
        emoji = emoji,
        tagsCsv = joinTags(tags),
        subpageOrderCsv = subpageOrder.toLongCsv(),
        isFullWidth = isFullWidth,
        trashTimestamp = trashTimestamp,
        serverTimestamp = timestamp,
        size = size,
        fileName = fileName,
        filePath = filePath,
        collectivePath = collectivePath,
        linkedPageIdsCsv = linkedPageIds.toLongCsv(),
        lastUserDisplayName = lastUserDisplayName,
        bodyMd = existingBody,
        bodyEtag = existingEtag,
        draftBodyMd = existingDraft,
        lastSyncedAt = now,
    )

internal fun PageEntity.toDomain(): Page =
    Page(
        id = id,
        collectiveId = collectiveId,
        parentId = parentId,
        title = title,
        emoji = emoji,
        tags = splitTags(tagsCsv),
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
        draftBodyMd = draftBodyMd,
    )
