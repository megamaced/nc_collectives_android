package com.megamaced.nccollectives.ui.navigation

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TabReselectionsTest {
    @Test
    fun aTapReachesOnlyItsOwnTabsScreen() =
        runTest(UnconfinedTestDispatcher()) {
            val reselections = TabReselections()
            val heard = mutableListOf<TopLevelTab>()
            TopLevelTab.entries.forEach { tab -> backgroundScope.launch { reselections.of(tab).collect { heard += tab } } }

            reselections.tapped(TopLevelTab.Favorites)
            reselections.tapped(TopLevelTab.Search)

            assertEquals(listOf(TopLevelTab.Favorites, TopLevelTab.Search), heard)
        }

    @Test
    fun eachTabsTapsAreOneFlow_soAnEffectKeyedOnItIsNotRestarted() {
        val reselections = TabReselections()
        assertSame(reselections.of(TopLevelTab.Home), reselections.of(TopLevelTab.Home))
    }
}
