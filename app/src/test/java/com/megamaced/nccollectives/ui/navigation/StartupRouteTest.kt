package com.megamaced.nccollectives.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [resolveStartupRoute] — the "Startup → open this collective" decision.
 *
 * The case worth having a test for is the difference between *the collective
 * was deleted* and *the cache hasn't loaded yet*: both look like "the id
 * isn't in the list", but only the first should clear the user's setting.
 */
class StartupRouteTest {
    @Test
    fun noDefaultSet_landsOnCollectiveList() {
        assertEquals(
            StartupRoute.CollectiveList,
            resolveStartupRoute(defaultCollectiveId = null, cachedCollectiveIds = listOf(1L, 2L)),
        )
    }

    @Test
    fun noDefaultSet_withEmptyCache_landsOnCollectiveList() {
        assertEquals(
            StartupRoute.CollectiveList,
            resolveStartupRoute(defaultCollectiveId = null, cachedCollectiveIds = emptyList()),
        )
    }

    @Test
    fun defaultPresentInCache_opensIt() {
        assertEquals(
            StartupRoute.OpenCollective(2L),
            resolveStartupRoute(defaultCollectiveId = 2L, cachedCollectiveIds = listOf(1L, 2L, 3L)),
        )
    }

    @Test
    fun defaultIsOnlyCachedCollective_opensIt() {
        assertEquals(
            StartupRoute.OpenCollective(7L),
            resolveStartupRoute(defaultCollectiveId = 7L, cachedCollectiveIds = listOf(7L)),
        )
    }

    @Test
    fun defaultMissingFromPopulatedCache_isStale() {
        // The collective was trashed or deleted server-side — the cache is
        // authoritative here, so forget the setting.
        assertEquals(
            StartupRoute.StaleDefault,
            resolveStartupRoute(defaultCollectiveId = 99L, cachedCollectiveIds = listOf(1L, 2L)),
        )
    }

    @Test
    fun defaultWithEmptyCache_landsOnListWithoutClearing() {
        // Cold cache (first launch of the process, nothing synced yet) must
        // not be mistaken for deletion: clearing here would silently drop a
        // setting the user still wants.
        assertEquals(
            StartupRoute.CollectiveList,
            resolveStartupRoute(defaultCollectiveId = 5L, cachedCollectiveIds = emptyList()),
        )
    }
}
