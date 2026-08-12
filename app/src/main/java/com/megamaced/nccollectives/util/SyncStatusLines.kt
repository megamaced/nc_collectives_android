package com.megamaced.nccollectives.util

import com.megamaced.nccollectives.data.prefs.SyncStatus
import java.util.concurrent.TimeUnit

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
 * Hand-rolled rather than `DateUtils.getRelativeTimeSpanString` so it stays
 * a pure function: this is the piece worth pinning with tests, and the
 * project's test source set is plain JVM (no Robolectric).
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
        "Last synced ${relativeTime(status.lastSuccessAt, now)}"
    }
    val error = status.lastFailureMessage
        ?.takeIf { status.lastFailureAt > 0L }
        ?.let { message -> "Last attempt ${relativeTime(status.lastFailureAt, now)} failed: $message" }
    return SyncStatusLines(summary = summary, error = error)
}

/**
 * Coarse relative time. Anything under a minute — including a [then] in the
 * future, which a device clock adjustment can produce — reads as "just now"
 * rather than exposing a negative interval.
 */
private fun relativeTime(
    then: Long,
    now: Long,
): String {
    val elapsedMs = now - then
    if (elapsedMs < TimeUnit.MINUTES.toMillis(1)) return "just now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs)
    if (minutes < 60) return plural(minutes, "minute")
    val hours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
    if (hours < 24) return plural(hours, "hour")
    return plural(TimeUnit.MILLISECONDS.toDays(elapsedMs), "day")
}

private fun plural(
    count: Long,
    unit: String,
): String = if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"
