package com.megamaced.nccollectives.util

import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.data.prefs.SyncStatus

/**
 * The two lines Settings shows for sync state: when it last worked, and —
 * only when there's something to say — why it isn't working now.
 */
data class SyncStatusLines(
    val summary: UiText,
    val error: UiText?,
)

/**
 * Render [status] relative to [now].
 *
 * A failure never hides the last success. "Last synced: 3 days ago" plus
 * "Last attempt failed (5 minutes ago): …" is the distinction the issue-5
 * reporter was missing — either line alone leaves them guessing whether
 * the data on screen is merely old or actively broken.
 */
fun syncStatusLines(
    status: SyncStatus,
    now: Long,
): SyncStatusLines {
    val summary = if (status.lastSuccessAt <= 0L) {
        uiText(R.string.sync_not_synced_yet)
    } else {
        uiText(R.string.sync_last_synced, relativeTimeAgo(status.lastSuccessAt, now))
    }
    val reason = status.lastFailure?.toUiText() ?: status.legacyFailureMessage?.let(UiText::Raw)
    val error = reason
        ?.takeIf { status.lastFailureAt > 0L }
        ?.let { uiText(R.string.sync_last_attempt_failed, relativeTimeAgo(status.lastFailureAt, now), it) }
    return SyncStatusLines(summary = summary, error = error)
}
