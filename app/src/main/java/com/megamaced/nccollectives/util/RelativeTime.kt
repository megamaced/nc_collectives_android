package com.megamaced.nccollectives.util

import java.util.concurrent.TimeUnit

/**
 * Coarse relative time — "just now", "5 minutes ago", "3 weeks ago".
 *
 * Hand-rolled rather than `DateUtils.getRelativeTimeSpanString` so it stays
 * a pure function: the wording is the part worth pinning with tests, and
 * the project's unit-test source set is plain JVM (no Robolectric).
 *
 * Anything under a minute — including a [then] in the future, which a
 * device clock adjustment can produce — reads as "just now" rather than
 * exposing a negative interval.
 *
 * Above a week the units are deliberately approximate: a week is 7 days, a
 * month 30 and a year 365. A page list is the caller that needs them, and
 * there "7 months ago" is the useful answer where "213 days ago" is not;
 * nothing downstream does arithmetic on these strings.
 */
fun relativeTimeAgo(
    then: Long,
    now: Long,
): String {
    val elapsedMs = now - then
    if (elapsedMs < TimeUnit.MINUTES.toMillis(1)) return "just now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs)
    if (minutes < 60) return plural(minutes, "minute")
    val hours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
    if (hours < 24) return plural(hours, "hour")
    val days = TimeUnit.MILLISECONDS.toDays(elapsedMs)
    if (days < 7) return plural(days, "day")
    if (days < 30) return plural(days / 7, "week")
    // Capped at 11: 364 days divided by a 30-day month is 12, and "12
    // months ago" immediately before "1 year ago" is a label no one wants
    // to read.
    if (days < 365) return plural(minOf(days / 30, 11L), "month")
    return plural(days / 365, "year")
}

private fun plural(
    count: Long,
    unit: String,
): String = if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"
