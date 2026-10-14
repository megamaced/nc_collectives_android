package com.megamaced.nccollectives.data.mapper

import com.megamaced.nccollectives.data.ServerStringValidation
import com.megamaced.nccollectives.data.api.dto.NotificationDto
import com.megamaced.nccollectives.data.db.entity.AlertEntity
import com.megamaced.nccollectives.domain.model.Alert
import com.megamaced.nccollectives.domain.model.AlertKind
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The notification as an alert, or null when it isn't about a collective
 * page. Decided from the notification's app, object and rich parameters
 * only — never from its subject, which is a translated sentence.
 *
 * Rich parameters only count when they name the same page as the
 * notification's object: a `page` parameter that disagrees, a parameter
 * without a usable id, or a malformed one, is treated as absent.
 *
 * - Collectives' own (`collectives`, object `page`): Collectives always sends
 *   `page` and `collective` parameters, so without both the notification is
 *   left out rather than guessed at. Updated when its `page` parameter links
 *   to the page, deleted when it has no link, since a deleted page has
 *   nothing to link to.
 * - Text's mentions (`text`, object `file`): the file id is the page id.
 *   Collectives' mention listener adds `collective` and `page` parameters
 *   when the file is a page; without them the mention is kept unverified,
 *   and shown only while the page is cached.
 */
internal fun NotificationDto.toAlertEntity(): AlertEntity? {
    val params = richParameters()
    val objectNumber = objectId.toLongOrNull() ?: return null
    val page = params[PARAM_PAGE]?.takeIf { it.id.toLongOrNull() == objectNumber }
    val collective = params[PARAM_COLLECTIVE]?.takeIf { it.id.toLongOrNull() != null }
    val (kind, verified) = when {
        app == APP_COLLECTIVES && objectType == OBJECT_PAGE -> {
            if (page == null || collective == null) return null
            (if (page.link != null) AlertKind.PageUpdated else AlertKind.PageDeleted) to true
        }

        app == APP_TEXT && objectType == OBJECT_FILE -> {
            AlertKind.Mention to (page != null && collective != null)
        }

        else -> {
            return null
        }
    }
    return AlertEntity(
        id = notificationId,
        kind = kind.name,
        pageId = objectNumber,
        collectiveId = collective?.id?.toLongOrNull(),
        actorName = params[PARAM_USER]?.name?.display(),
        pageTitle = page?.name?.display(),
        collectiveName = collective?.name?.display(),
        subject = ServerStringValidation.sanitiseDisplay(subject),
        timestamp = epochSeconds(datetime),
        verified = verified,
        seen = false,
    )
}

/** Null for a row whose kind this version doesn't know, which is skipped rather than fatal. */
internal fun AlertEntity.toDomain(): Alert? =
    Alert(
        id = id,
        kind = AlertKind.entries.firstOrNull { it.name == kind } ?: return null,
        pageId = pageId,
        collectiveId = collectiveId,
        actorName = actorName,
        pageTitle = pageTitle,
        collectiveName = collectiveName,
        subject = subject,
        timestamp = timestamp,
        seen = seen,
    )

// S-18: server-provided display strings pass the trust boundary here.
private fun String.display(): String? = ServerStringValidation.sanitiseDisplay(this).takeIf { it.isNotBlank() }

/** 0 for a date the server sent in a shape this can't read: the alert still shows, undated. */
private fun epochSeconds(iso: String): Long =
    try {
        OffsetDateTime.parse(iso).toEpochSecond()
    } catch (_: DateTimeParseException) {
        0L
    }

private const val APP_COLLECTIVES = "collectives"
private const val APP_TEXT = "text"
private const val OBJECT_PAGE = "page"
private const val OBJECT_FILE = "file"
private const val PARAM_PAGE = "page"
private const val PARAM_COLLECTIVE = "collective"
private const val PARAM_USER = "user"
