package com.megamaced.nccollectives.ui.navigation

/** Where the app should land once authentication has resolved. */
internal sealed interface StartupRoute {
    /** Land on the collective picker — the default, and the safe fallback. */
    data object CollectiveList : StartupRoute

    /** Open this collective's page tree straight away (Settings → Startup). */
    data class OpenCollective(
        val collectiveId: Long,
    ) : StartupRoute

    /**
     * A default is stored but that collective no longer exists. Land on the
     * list *and* clear the setting, so it stops pointing at something gone.
     */
    data object StaleDefault : StartupRoute
}

/**
 * Decide the launch destination from the stored default and the ids currently
 * in the Room cache. Pure so the interesting case — telling "the collective
 * was deleted" apart from "the cache hasn't loaded" — can be pinned by tests
 * rather than reasoned about at a call site that also does I/O.
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
    defaultCollectiveId: Long?,
    cachedCollectiveIds: List<Long>,
): StartupRoute {
    if (defaultCollectiveId == null) return StartupRoute.CollectiveList
    if (defaultCollectiveId in cachedCollectiveIds) {
        return StartupRoute.OpenCollective(defaultCollectiveId)
    }
    if (cachedCollectiveIds.isEmpty()) return StartupRoute.CollectiveList
    return StartupRoute.StaleDefault
}
