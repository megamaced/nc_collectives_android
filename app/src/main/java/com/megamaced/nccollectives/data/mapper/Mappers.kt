package com.megamaced.nccollectives.data.mapper

import com.megamaced.nccollectives.data.ServerStringValidation
import com.megamaced.nccollectives.data.api.dto.CollectiveDto
import com.megamaced.nccollectives.data.api.dto.PageDto
import com.megamaced.nccollectives.data.db.dao.PageListRow
import com.megamaced.nccollectives.data.db.entity.CollectiveEntity
import com.megamaced.nccollectives.data.db.entity.PageEntity
import com.megamaced.nccollectives.data.joinTags
import com.megamaced.nccollectives.data.splitTags
import com.megamaced.nccollectives.data.toLongCsv
import com.megamaced.nccollectives.data.toLongCsvList
import com.megamaced.nccollectives.data.toLongCsvSet
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageListItem

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

/**
 * [tagNamesById] resolves the numeric tag ids the server sends into names.
 * B-69: null means the tag lookup *failed*, which is a different thing from
 * a collective that has no tags — see the `tagsCsv` assignment below.
 * [existingTagsCsv] is then what the row keeps, so it is threaded through
 * from the cached row exactly like [existingBody] / [existingEtag] /
 * [existingDraft].
 */
internal fun PageDto.toEntity(
    collectiveId: Long,
    now: Long,
    existingBody: String?,
    existingEtag: String?,
    existingDraft: String?,
    existingTagsCsv: String? = null,
    tagNamesById: Map<Long, String>? = null,
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
        //
        // B-69: a null map means we never got the tag list. Resolving against
        // an empty one instead would write `tagsCsv = ""` for every page in
        // the collective — one 500 from the tags endpoint and Tag Browse is
        // empty for the whole account until a later refresh happens to
        // succeed. Keep what the row already had.
        tagsCsv = if (tagNamesById == null) {
            existingTagsCsv.orEmpty()
        } else {
            joinTags(tags.mapNotNull { tagNamesById[it] })
        },
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

/**
 * R-54: the list path's mapper, and the cheapest of the three in this file.
 *
 * It parses two CSV columns rather than the entity mapper's three — linked
 * ids aren't list-visible ([PageListItem] says why) — and both of those
 * yield the shared `emptyList()` for a row with no tags and no explicit
 * sibling order, which most rows are. On a 200-page collective that is the
 * difference between hundreds of list allocations per emission and none.
 */
internal fun PageListRow.toDomain(): PageListItem =
    PageListItem(
        id = id,
        collectiveId = collectiveId,
        parentId = parentId,
        title = title,
        emoji = emoji,
        tags = splitTags(tagsCsv),
        subpageOrder = subpageOrderCsv.toLongCsvList(),
        trashed = trashTimestamp != null,
        serverTimestamp = serverTimestamp,
        lastUserDisplayName = lastUserDisplayName,
        hasDraft = hasDraft,
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
