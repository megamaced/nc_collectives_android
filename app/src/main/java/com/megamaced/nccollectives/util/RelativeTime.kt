package com.megamaced.nccollectives.util

import androidx.annotation.PluralsRes
import com.megamaced.nccollectives.R
import java.util.concurrent.TimeUnit

/**
 * Coarse relative time — "just now", "5 minutes ago", "3 weeks ago" — as
 * a phrase that stands on its own. Sentences that show it set it off ("Last
 * synced: 5 minutes ago", "Edited by Ada · 5 minutes ago") rather than
 * building it into their grammar, which wouldn't survive translation.
 *
 * Anything under a minute — including a [then] in the future, which a
 * device clock adjustment can produce — reads as "just now" rather than
 * exposing a negative interval.
 *
 * Above a week the units are deliberately approximate: a week is 7 days, a
 * month 30 and a year 365. A page list is the caller that needs them, and
 * there "7 months ago" is the useful answer where "213 days ago" is not;
 * nothing downstream does arithmetic on these phrases.
 */
fun relativeTimeAgo(
    then: Long,
    now: Long,
): UiText {
    val elapsedMs = now - then
    if (elapsedMs < TimeUnit.MINUTES.toMillis(1)) return uiText(R.string.time_just_now)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs)
    if (minutes < 60) return ago(R.plurals.time_minutes_ago, minutes)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
    if (hours < 24) return ago(R.plurals.time_hours_ago, hours)
    val days = TimeUnit.MILLISECONDS.toDays(elapsedMs)
    if (days < 7) return ago(R.plurals.time_days_ago, days)
    if (days < 30) return ago(R.plurals.time_weeks_ago, days / 7)
    // Capped at 11: 364 days divided by a 30-day month is 12, and "12
    // months ago" immediately before "1 year ago" is a label no one wants
    // to read.
    if (days < 365) return ago(R.plurals.time_months_ago, minOf(days / 30, 11L))
    return ago(R.plurals.time_years_ago, days / 365)
}

private fun ago(
    @PluralsRes id: Int,
    count: Long,
): UiText = UiText.Plural(id, count.toInt())
