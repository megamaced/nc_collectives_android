package com.megamaced.nccollectives.ui.navigation

import com.megamaced.nccollectives.data.prefs.StartScreen

/** Where the app should land once authentication has resolved. */
internal sealed interface StartupRoute {
    /** Stay on the Home tab, the navigation graph's start. */
    data object Home : StartupRoute

    /** Select the Collectives tab, on the collective list. */
    data object CollectiveList : StartupRoute

    /**
     * Open this collective's page tree inside the Collectives tab, so Back
     * still reaches the collective list (Settings → Startup).
     */
    data class OpenCollective(
        val collectiveId: Long,
    ) : StartupRoute

    /**
     * A startup collective is stored but no longer exists. Land on
     * [fallback] *and* clear the setting, so it stops pointing at something
     * gone.
     */
    data class StaleDefault(
        val fallback: StartupRoute,
    ) : StartupRoute
}

/**
 * Decide the launch destination from the stored settings and the ids
 * currently in the Room cache. Pure so the interesting case — telling "the
 * collective was deleted" apart from "the cache hasn't loaded" — can be pinned
 * by tests rather than reasoned about at a call site that also does I/O.
 *
 * [cachedCollectiveIds] must come from a query that already excludes trashed
 * collectives (`CollectiveDao.list`/`observeAll` both filter
 * `trashTimestamp IS NULL`), which is what makes membership a sufficient
 * liveness check.
 *
 * An **empty** cache is deliberately not treated as "deleted": on a cold
 * start the cache may simply not be populated yet, and clearing the user's
 * setting because we asked too early would be a silent, confusing data loss.
 */
internal fun resolveStartupRoute(
    startScreen: StartScreen,
    defaultCollectiveId: Long?,
    cachedCollectiveIds: List<Long>,
): StartupRoute {
    val screen =
        when (startScreen) {
            StartScreen.Home -> StartupRoute.Home
            StartScreen.CollectiveList -> StartupRoute.CollectiveList
        }
    if (defaultCollectiveId == null) return screen
    if (defaultCollectiveId in cachedCollectiveIds) {
        return StartupRoute.OpenCollective(defaultCollectiveId)
    }
    if (cachedCollectiveIds.isEmpty()) return screen
    return StartupRoute.StaleDefault(fallback = screen)
}
