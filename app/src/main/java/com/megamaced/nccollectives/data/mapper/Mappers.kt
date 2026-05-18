package com.megamaced.nccollectives.data.mapper

import com.megamaced.nccollectives.data.ServerStringValidation
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
        // S-18: server-provided display strings pass through the trust
        // boundary before they reach Room (and from there nav route args
        // + SQL LIKE patterns). Strips control chars + caps length so a
        // misbehaving Nextcloud can't smuggle in newlines that break
        // Compose Navigation arg parsing or oversized titles that bloat
        // every observe-pages query.
        name = ServerStringValidation.sanitiseDisplay(name),
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
    tagNamesById: Map<Long, String> = emptyMap(),
): PageEntity =
    PageEntity(
        id = id,
        collectiveId = collectiveId,
        parentId = parentId,
        // S-18: same trust-boundary sanitisation as collective name —
        // the title flows into nav route args (TagBrowse / PageView)
        // and the recent-pages strip; control chars or megabyte-titles
        // from a misbehaving server would otherwise reach Compose
        // Navigation's path parser unchecked.
        title = ServerStringValidation.sanitiseDisplay(title),
        emoji = emoji,
        // Server returns tag IDs only; resolve to names via the per-collective
        // tag map. Unknown IDs (newly created tag we haven't refreshed yet) are
        // dropped rather than rendered as bare numbers.
        tagsCsv = joinTags(tags.mapNotNull { tagNamesById[it] }),
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
