package com.megamaced.nccollectives.util

/**
 * Minimum gap between two *automatic* refreshes of the same screen. Only
 * throttles the ones the user didn't ask for — pull-to-refresh and "Sync
 * now" always run, because a user who explicitly asks is entitled to a
 * round-trip even if one just happened.
 *
 * 30s is chosen against the thing being throttled: a screen returning to
 * the foreground. Short enough that stepping into a page and back gets you
 * current data, long enough that bouncing between two screens doesn't turn
 * into a request per tap.
 */
const val AUTO_REFRESH_MIN_INTERVAL_MS = 30_000L

/**
 * Whether an automatic refresh should run now, given when the last one
 * started ([lastRefreshAt], 0 meaning "never").
 *
 * A [now] earlier than [lastRefreshAt] means the device clock moved
 * backwards (NTP correction, manual change, timezone-adjacent shenanigans).
 * Refreshing is the safe answer there: the alternative is a screen that
 * refuses to update itself until wall-clock time catches back up.
 */
fun shouldAutoRefresh(
    lastRefreshAt: Long,
    now: Long,
    minIntervalMs: Long = AUTO_REFRESH_MIN_INTERVAL_MS,
): Boolean {
    if (lastRefreshAt <= 0L) return true
    if (now < lastRefreshAt) return true
    return now - lastRefreshAt >= minIntervalMs
}
