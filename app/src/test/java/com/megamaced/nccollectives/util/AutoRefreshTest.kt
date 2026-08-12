package com.megamaced.nccollectives.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the resume-refresh throttle (B-58). */
class AutoRefreshTest {
    @Test
    fun neverRefreshed_refreshes() {
        assertTrue(shouldAutoRefresh(lastRefreshAt = 0L, now = 1_000L))
    }

    @Test
    fun justRefreshed_doesNot() {
        // The case this exists for: `init` refreshes, then the screen's first
        // resume fires immediately afterwards. Without the throttle every
        // screen entry costs two round-trips.
        assertFalse(shouldAutoRefresh(lastRefreshAt = 100_000L, now = 100_500L))
    }

    @Test
    fun exactlyAtInterval_refreshes() {
        assertTrue(shouldAutoRefresh(lastRefreshAt = 100_000L, now = 100_000L + AUTO_REFRESH_MIN_INTERVAL_MS))
    }

    @Test
    fun justUnderInterval_doesNot() {
        assertFalse(shouldAutoRefresh(lastRefreshAt = 100_000L, now = 100_000L + AUTO_REFRESH_MIN_INTERVAL_MS - 1))
    }

    @Test
    fun clockMovedBackwards_refreshes() {
        // An NTP correction or manual clock change must not leave a screen
        // refusing to refresh until wall-clock time catches back up.
        assertTrue(shouldAutoRefresh(lastRefreshAt = 5_000_000L, now = 1_000L))
    }

    @Test
    fun customInterval_isHonoured() {
        assertFalse(shouldAutoRefresh(lastRefreshAt = 1_000L, now = 2_000L, minIntervalMs = 5_000L))
        assertTrue(shouldAutoRefresh(lastRefreshAt = 1_000L, now = 6_000L, minIntervalMs = 5_000L))
    }
}
