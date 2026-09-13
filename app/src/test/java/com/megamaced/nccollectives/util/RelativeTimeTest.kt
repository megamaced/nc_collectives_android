package com.megamaced.nccollectives.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pins the relative-time wording. Two screens render these strings and a
 * third reads them out of Settings, so a change here is a change to what
 * the app says — which is exactly what this should make loud.
 */
class RelativeTimeTest {
    private val now = TimeUnit.DAYS.toMillis(20_000)

    private fun ago(
        amount: Long,
        unit: TimeUnit,
    ): String = relativeTimeAgo(now - unit.toMillis(amount), now)

    @Test
    fun underAMinute_isJustNow() {
        assertEquals("just now", relativeTimeAgo(now, now))
        assertEquals("just now", ago(59, TimeUnit.SECONDS))
    }

    @Test
    fun futureTimestamp_isJustNow() {
        // A device clock correction must not produce "-3 minutes ago".
        assertEquals("just now", relativeTimeAgo(now + TimeUnit.HOURS.toMillis(3), now))
    }

    @Test
    fun minutesHoursAndDays() {
        assertEquals("1 minute ago", ago(1, TimeUnit.MINUTES))
        assertEquals("10 minutes ago", ago(10, TimeUnit.MINUTES))
        assertEquals("59 minutes ago", ago(59, TimeUnit.MINUTES))
        assertEquals("1 hour ago", ago(1, TimeUnit.HOURS))
        assertEquals("23 hours ago", ago(23, TimeUnit.HOURS))
        assertEquals("1 day ago", ago(1, TimeUnit.DAYS))
        assertEquals("2 days ago", ago(2, TimeUnit.DAYS))
        assertEquals("6 days ago", ago(6, TimeUnit.DAYS))
    }

    @Test
    fun coarseUnitsAboveAWeek() {
        // The reason this function exists rather than a third copy of
        // DateUtils: a wiki page edited two years ago must not read as
        // "731 days ago".
        assertEquals("1 week ago", ago(7, TimeUnit.DAYS))
        assertEquals("4 weeks ago", ago(29, TimeUnit.DAYS))
        assertEquals("1 month ago", ago(30, TimeUnit.DAYS))
        assertEquals("11 months ago", ago(364, TimeUnit.DAYS))
        assertEquals("1 year ago", ago(365, TimeUnit.DAYS))
        assertEquals("2 years ago", ago(731, TimeUnit.DAYS))
    }

    @Test
    fun boundariesRoundDown() {
        // Each unit takes effect only once it is genuinely reached.
        assertEquals("59 minutes ago", ago(3599, TimeUnit.SECONDS))
        assertEquals("1 hour ago", ago(3600, TimeUnit.SECONDS))
        assertEquals("23 hours ago", ago(1439, TimeUnit.MINUTES))
        assertEquals("1 day ago", ago(1440, TimeUnit.MINUTES))
    }
}
