package com.megamaced.nccollectives.util

import com.megamaced.nccollectives.data.prefs.SyncStatus

/**
 * The two lines Settings shows for sync state: when it last worked, and —
 * only when there's something to say — why it isn't working now.
 */
data class SyncStatusLines(
    val summary: String,
    val error: String?,
)

/**
 * Render [status] relative to [now].
 *
 * Wording comes from [relativeTimeAgo], shared with the page list so the
 * two never drift apart.
 *
 * A failure never hides the last success. "Last synced 3 days ago" plus
 * "Last attempt failed: …" is the distinction the issue-5 reporter was
 * missing — either line alone leaves them guessing whether the data on
 * screen is merely old or actively broken.
 */
fun syncStatusLines(
    status: SyncStatus,
    now: Long,
): SyncStatusLines {
    val summary = if (status.lastSuccessAt <= 0L) {
        "Not synced yet"
    } else {
        "Last synced ${relativeTimeAgo(status.lastSuccessAt, now)}"
    }
    val error = status.lastFailureMessage
        ?.takeIf { status.lastFailureAt > 0L }
        ?.let { message -> "Last attempt ${relativeTimeAgo(status.lastFailureAt, now)} failed: $message" }
    return SyncStatusLines(summary = summary, error = error)
}
