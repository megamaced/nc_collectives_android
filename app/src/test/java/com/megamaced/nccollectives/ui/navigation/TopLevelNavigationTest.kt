package com.megamaced.nccollectives.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the tab back-stack behaviour of [navigateToTab] and [reselectTab]
 * against a graph shaped like `NcCollectivesNavHost`'s: one nested graph per
 * tab, tab-prefixed detail routes, Home as the start graph, share capture
 * outside every tab, and [ReturnHomeOnBack] on the other tabs' roots. The
 * screens are placeholders; the routes are the real ones, and Back goes
 * through the activity's dispatcher as the system's would.
 */
@RunWith(AndroidJUnit4::class)
class TopLevelNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    private val reselections = TabReselections()
    private val reselected = mutableListOf<TopLevelTab>()
    private val listening = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        TopLevelTab.entries.forEach { tab -> listening.launch { reselections.of(tab).collect { reselected += tab } } }
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = TopLevelTab.Home.graphRoute) {
                TopLevelTab.entries.forEach { tab ->
                    navigation(route = tab.graphRoute, startDestination = tab.rootRoute) {
                        composable(tab.rootRoute) {
                            if (tab != TopLevelTab.Home) ReturnHomeOnBack(nav)
                            Text(stringResource(tab.label))
                        }
                        composable(
                            route = Destination.PageTree.pattern(tab),
                            arguments = listOf(
                                navArgument(Destination.PageTree.ARG_COLLECTIVE_ID) { type = NavType.LongType },
                            ),
                        ) { Text("tree") }
                        composable(
                            route = Destination.PageView.pattern(tab),
                            arguments = listOf(
                                navArgument(Destination.PageView.ARG_PAGE_ID) { type = NavType.LongType },
                            ),
                        ) { Text("page") }
                        composable(
                            route = Destination.PageEdit.pattern(tab),
                            arguments = listOf(
                                navArgument(Destination.PageEdit.ARG_PAGE_ID) { type = NavType.LongType },
                            ),
                        ) { Text("edit") }
                    }
                }
                composable(Destination.ShareCapture.ROUTE) { Text("share") }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        listening.cancel()
    }

    private fun onNav(block: NavHostController.() -> Unit) {
        compose.runOnIdle { nav.block() }
        compose.waitForIdle()
    }

    /** System Back, through the same dispatcher the NavHost listens on. */
    private fun pressBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private val route: String? get() = nav.currentDestination?.route

    private val tab: TopLevelTab? get() = nav.currentDestination?.topLevelTab()

    private val pageId: Long? get() = nav.currentBackStackEntry?.arguments?.getLong(Destination.PageView.ARG_PAGE_ID)

    @Test
    fun startsOnTheHomeRoot() {
        compose.runOnIdle {
            assertEquals(TopLevelTab.Home.rootRoute, route)
            assertEquals(TopLevelTab.Home, tab)
        }
    }

    @Test
    fun everyDetailRouteReportsTheTabItWasOpenedIn() {
        TopLevelTab.entries.forEach { target ->
            onNav {
                navigateToTab(target)
                navigate(Destination.PageView.route(target, 42L))
            }
            compose.runOnIdle {
                assertEquals(target, tab)
                assertEquals(Destination.PageView.pattern(target), route)
                assertEquals(42L, pageId)
            }
        }
    }

    @Test
    fun aTabsStackIsRestoredAfterVisitingAnotherTab() {
        onNav {
            navigateToTab(TopLevelTab.Collectives)
            navigate(Destination.PageTree.route(TopLevelTab.Collectives, 3L))
            navigate(Destination.PageView.route(TopLevelTab.Collectives, 5L))
            navigateToTab(TopLevelTab.Search)
        }
        compose.runOnIdle { assertEquals(TopLevelTab.Search.rootRoute, route) }

        onNav { navigateToTab(TopLevelTab.Collectives) }
        compose.runOnIdle {
            assertEquals(Destination.PageView.pattern(TopLevelTab.Collectives), route)
            assertEquals(5L, pageId)
        }

        // The restored stack is intact underneath, not just its top entry.
        pressBack()
        compose.runOnIdle { assertEquals(Destination.PageTree.pattern(TopLevelTab.Collectives), route) }
        pressBack()
        compose.runOnIdle { assertEquals(TopLevelTab.Collectives.rootRoute, route) }
    }

    @Test
    fun backFromAnotherTabsRootLandsOnTheHomeRoot() {
        TopLevelTab.entries.filter { it != TopLevelTab.Home }.forEach { other ->
            onNav { navigateToTab(other) }
            pressBack()
            compose.runOnIdle {
                assertEquals(TopLevelTab.Home.rootRoute, route)
                assertEquals(TopLevelTab.Home, tab)
            }
        }
    }

    @Test
    fun backFromAnotherTabsRootReturnsToHomeAsTheUserLeftIt() {
        onNav {
            navigate(Destination.PageView.route(TopLevelTab.Home, 9L))
            navigateToTab(TopLevelTab.Favorites)
        }
        pressBack()
        compose.runOnIdle {
            assertEquals(Destination.PageView.pattern(TopLevelTab.Home), route)
            assertEquals(9L, pageId)
        }
        pressBack()
        compose.runOnIdle { assertEquals(TopLevelTab.Home.rootRoute, route) }
    }

    @Test
    fun backThroughHomeLeavesNoStaleStack_soTheNextHomePageIsTheOneRestored() {
        onNav {
            navigate(Destination.PageView.route(TopLevelTab.Home, 9L))
            navigateToTab(TopLevelTab.Favorites)
        }
        pressBack() // Home, page 9
        pressBack() // Home root
        onNav {
            navigate(Destination.PageView.route(TopLevelTab.Home, 10L))
            navigateToTab(TopLevelTab.Search)
            navigateToTab(TopLevelTab.Home)
        }
        compose.runOnIdle {
            assertEquals(Destination.PageView.pattern(TopLevelTab.Home), route)
            assertEquals(10L, pageId)
        }
    }

    @Test
    fun reselectingHomeAfterBackLeavesNothingToResurrect() {
        onNav {
            navigate(Destination.PageView.route(TopLevelTab.Home, 9L))
            navigateToTab(TopLevelTab.Favorites)
        }
        pressBack()
        onNav {
            reselectTab(TopLevelTab.Home)
            navigateToTab(TopLevelTab.Search)
            navigateToTab(TopLevelTab.Home)
        }
        compose.runOnIdle { assertEquals(TopLevelTab.Home.rootRoute, route) }
    }

    @Test
    fun homesStackIsRestoredAfterVisitingAnotherTab() {
        onNav {
            navigate(Destination.PageView.route(TopLevelTab.Home, 9L))
            navigateToTab(TopLevelTab.Favorites)
            navigateToTab(TopLevelTab.Home)
        }
        compose.runOnIdle {
            assertEquals(Destination.PageView.pattern(TopLevelTab.Home), route)
            assertEquals(9L, pageId)
        }
    }

    @Test
    fun homesStackIsRestoredAfterEarlierTabSwitches() {
        onNav {
            navigateToTab(TopLevelTab.Search)
            navigateToTab(TopLevelTab.Home)
            navigate(Destination.PageView.route(TopLevelTab.Home, 9L))
            navigateToTab(TopLevelTab.Favorites)
            navigateToTab(TopLevelTab.Search)
            navigateToTab(TopLevelTab.Home)
        }
        compose.runOnIdle { assertEquals(9L, pageId) }
    }

    @Test
    fun homesRootNeverRestoresAnotherTabsStack() {
        onNav {
            navigateToTab(TopLevelTab.Collectives)
            navigate(Destination.PageView.route(TopLevelTab.Collectives, 5L))
            navigateToTab(TopLevelTab.Search)
            navigate(Destination.PageView.route(TopLevelTab.Search, 7L))
            navigateToTab(TopLevelTab.Home)
        }
        compose.runOnIdle { assertEquals(TopLevelTab.Home.rootRoute, route) }
    }

    @Test
    fun switchingTabsKeepsOneEntryPerTabRootOnTheStack() {
        // Tapping back and forth must not pile up roots: Back from any tab's
        // root still lands on Home's root in one step.
        onNav {
            navigateToTab(TopLevelTab.Search)
            navigateToTab(TopLevelTab.Favorites)
            navigateToTab(TopLevelTab.Search)
            navigateToTab(TopLevelTab.Alerts)
        }
        pressBack()
        compose.runOnIdle { assertEquals(TopLevelTab.Home.rootRoute, route) }
    }

    @Test
    fun backOnADetailStillPopsWithinItsTab() {
        onNav {
            navigateToTab(TopLevelTab.Search)
            navigate(Destination.PageView.route(TopLevelTab.Search, 4L))
        }
        pressBack()
        compose.runOnIdle { assertEquals(TopLevelTab.Search.rootRoute, route) }
    }

    @Test
    fun reselectingATabReturnsToItsRootAndDiscardsTheDetails() {
        onNav {
            navigateToTab(TopLevelTab.Collectives)
            navigate(Destination.PageTree.route(TopLevelTab.Collectives, 3L))
            navigate(Destination.PageView.route(TopLevelTab.Collectives, 5L))
        }
        var popped = false
        onNav { popped = reselectTab(TopLevelTab.Collectives) }
        compose.runOnIdle {
            assertTrue(popped)
            assertEquals(TopLevelTab.Collectives.rootRoute, route)
        }

        // Discarded, not saved: leaving and coming back finds the root.
        onNav {
            navigateToTab(TopLevelTab.Search)
            navigateToTab(TopLevelTab.Collectives)
        }
        compose.runOnIdle { assertEquals(TopLevelTab.Collectives.rootRoute, route) }
    }

    @Test
    fun reselectingATabAtItsRootIsANoOp() {
        onNav { navigateToTab(TopLevelTab.Search) }
        var popped = true
        onNav { popped = reselectTab(TopLevelTab.Search) }
        compose.runOnIdle {
            assertFalse(popped)
            assertEquals(TopLevelTab.Search.rootRoute, route)
        }
    }

    @Test
    fun aTapOnTheTabShowingItsRootAsksThatScreenToGoBackToItsTop() {
        onNav { navigateToTab(TopLevelTab.Collectives) }

        onNav { onTabClicked(TopLevelTab.Collectives, selected = true, reselections) }

        compose.runOnIdle {
            assertEquals(listOf(TopLevelTab.Collectives), reselected)
            assertEquals(TopLevelTab.Collectives.rootRoute, route)
        }
    }

    @Test
    fun aTapOnTheTabShowingADetailOnlyReturnsToItsRoot() {
        onNav {
            navigateToTab(TopLevelTab.Collectives)
            navigate(Destination.PageTree.route(TopLevelTab.Collectives, 3L))
        }

        onNav { onTabClicked(TopLevelTab.Collectives, selected = true, reselections) }

        compose.runOnIdle {
            assertEquals(TopLevelTab.Collectives.rootRoute, route)
            assertTrue(reselected.isEmpty())
        }
    }

    @Test
    fun aTapOnAnotherTabOnlySwitchesToIt() {
        onNav { onTabClicked(TopLevelTab.Search, selected = false, reselections) }

        compose.runOnIdle {
            assertEquals(TopLevelTab.Search.rootRoute, route)
            assertTrue(reselected.isEmpty())
        }
    }

    @Test
    fun shareCaptureBelongsToNoTab_andDismissingItReturnsToTheStackBeneath() {
        onNav {
            navigateToTab(TopLevelTab.Favorites)
            navigate(Destination.PageView.route(TopLevelTab.Favorites, 7L))
            navigate(Destination.ShareCapture.ROUTE)
        }
        compose.runOnIdle {
            assertNull(tab)
            assertFalse(nav.currentDestination!!.showsNavigationBar())
        }
        onNav { popBackStack() }
        compose.runOnIdle {
            assertEquals(Destination.PageView.pattern(TopLevelTab.Favorites), route)
            assertEquals(7L, pageId)
        }
    }

    @Test
    fun theEditorsHideTheNavigationBar_andTheirPageShowsIt() {
        onNav {
            navigateToTab(TopLevelTab.Search)
            navigate(Destination.PageView.route(TopLevelTab.Search, 1L))
        }
        compose.runOnIdle { assertTrue(nav.currentDestination!!.showsNavigationBar()) }
        onNav { navigate(Destination.PageEdit.route(TopLevelTab.Search, 1L)) }
        compose.runOnIdle {
            assertFalse(nav.currentDestination!!.showsNavigationBar())
            assertEquals(TopLevelTab.Search, tab)
        }
    }
}
