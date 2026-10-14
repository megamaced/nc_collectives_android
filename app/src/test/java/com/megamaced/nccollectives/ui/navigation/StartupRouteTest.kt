package com.megamaced.nccollectives.ui.navigation

import com.megamaced.nccollectives.data.prefs.StartScreen
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [resolveStartupRoute] — the Settings → Startup decision.
 *
 * The case worth having a test for is the difference between *the collective
 * was deleted* and *the cache hasn't loaded yet*: both look like "the id
 * isn't in the list", but only the first should clear the user's setting.
 */
class StartupRouteTest {
    @Test
    fun noDefaultSet_landsOnTheStartScreen() {
        assertEquals(
            StartupRoute.Home,
            resolveStartupRoute(StartScreen.Home, defaultCollectiveId = null, cachedCollectiveIds = listOf(1L, 2L)),
        )
        assertEquals(
            StartupRoute.CollectiveList,
            resolveStartupRoute(
                StartScreen.CollectiveList,
                defaultCollectiveId = null,
                cachedCollectiveIds = listOf(1L, 2L),
            ),
        )
    }

    @Test
    fun noDefaultSet_withEmptyCache_landsOnTheStartScreen() {
        assertEquals(
            StartupRoute.Home,
            resolveStartupRoute(StartScreen.Home, defaultCollectiveId = null, cachedCollectiveIds = emptyList()),
        )
        assertEquals(
            StartupRoute.CollectiveList,
            resolveStartupRoute(
                StartScreen.CollectiveList,
                defaultCollectiveId = null,
                cachedCollectiveIds = emptyList(),
            ),
        )
    }

    @Test
    fun defaultPresentInCache_opensIt_whateverTheStartScreen() {
        StartScreen.entries.forEach { screen ->
            assertEquals(
                StartupRoute.OpenCollective(2L),
                resolveStartupRoute(screen, defaultCollectiveId = 2L, cachedCollectiveIds = listOf(1L, 2L, 3L)),
            )
        }
    }

    @Test
    fun defaultIsOnlyCachedCollective_opensIt() {
        assertEquals(
            StartupRoute.OpenCollective(7L),
            resolveStartupRoute(StartScreen.Home, defaultCollectiveId = 7L, cachedCollectiveIds = listOf(7L)),
        )
    }

    @Test
    fun defaultMissingFromPopulatedCache_isStale_andFallsBackToTheStartScreen() {
        // The collective was trashed or deleted server-side — the cache is
        // authoritative here, so forget the setting.
        assertEquals(
            StartupRoute.StaleDefault(fallback = StartupRoute.Home),
            resolveStartupRoute(StartScreen.Home, defaultCollectiveId = 99L, cachedCollectiveIds = listOf(1L, 2L)),
        )
        assertEquals(
            StartupRoute.StaleDefault(fallback = StartupRoute.CollectiveList),
            resolveStartupRoute(
                StartScreen.CollectiveList,
                defaultCollectiveId = 99L,
                cachedCollectiveIds = listOf(1L, 2L),
            ),
        )
    }

    @Test
    fun defaultWithEmptyCache_landsOnTheStartScreenWithoutClearing() {
        // Cold cache (first launch of the process, nothing synced yet) must
        // not be mistaken for deletion: clearing here would silently drop a
        // setting the user still wants.
        assertEquals(
            StartupRoute.Home,
            resolveStartupRoute(StartScreen.Home, defaultCollectiveId = 5L, cachedCollectiveIds = emptyList()),
        )
    }
}
