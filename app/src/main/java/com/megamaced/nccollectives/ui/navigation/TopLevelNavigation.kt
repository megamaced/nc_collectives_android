package com.megamaced.nccollectives.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination

/** The tab whose graph contains this destination, or null outside every tab (share capture). */
internal fun NavDestination.topLevelTab(): TopLevelTab? =
    hierarchy.firstNotNullOfOrNull { node ->
        TopLevelTab.entries.firstOrNull { it.graphRoute == node.route }
    }

/**
 * Switch to [tab], keeping every tab's back stack.
 *
 * The stack being left is popped back to the Home root and saved;
 * [tab]'s saved stack, if it has one, is restored. Home's own root is never
 * popped, so it is always at the bottom of the back stack.
 */
internal fun NavController.navigateToTab(tab: TopLevelTab) {
    navigate(tab.graphRoute) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Back on a tab root other than Home's returns to Home as the user left it.
 *
 * Not a plain pop. Navigation files Home's saved stack under Home's root the
 * first time Home is left, and never replaces that entry: a pop would land on
 * Home's root with the old stack still filed, so the next stack saved from
 * Home would be dropped and the old one restored on the next visit. Switching
 * tabs restores the saved stack, and restoring it is what clears the entry.
 *
 * Call it from the tab root's composable. Its callback then registers after
 * the NavHost's own, so it takes precedence while that root is showing.
 */
@Composable
internal fun ReturnHomeOnBack(navController: NavController) {
    BackHandler { navController.navigateToTab(TopLevelTab.Home) }
}

/**
 * The user tapped the tab they are already on: return to its root,
 * discarding the screens above it rather than saving them.
 *
 * Returns false when the root is already showing, which is the caller's cue
 * to scroll that screen back to its top instead.
 */
internal fun NavController.reselectTab(tab: TopLevelTab): Boolean {
    if (currentDestination?.route == tab.rootRoute) return false
    return popBackStack(tab.rootRoute, inclusive = false)
}

/**
 * A tap on [tab] in the navigation bar or rail. Another tab is switched to.
 * The current one returns to its root, and when its root is already
 * showing, that screen goes back to its top.
 */
internal fun NavController.onTabClicked(
    tab: TopLevelTab,
    selected: Boolean,
    reselections: TabReselections,
) {
    when {
        !selected -> navigateToTab(tab)
        !reselectTab(tab) -> reselections.tapped(tab)
    }
}

/**
 * Destinations that take the whole screen, without the navigation bar.
 *
 * The editors: the native editor keeps its formatting toolbar above the
 * keyboard, and the collaborative editor has Nextcloud Text's own toolbar
 * pinned to the bottom, so a navigation bar would collide with both. Leaving
 * the editor through a tab would also bypass its save and discard prompts.
 *
 * Share capture and add-account are flows to finish or cancel, not places
 * to navigate away from.
 */
internal fun NavDestination.showsNavigationBar(): Boolean = route !in fullScreenRoutes

private val fullScreenRoutes: Set<String> =
    buildSet {
        TopLevelTab.entries.forEach { tab ->
            add(Destination.PageEdit.pattern(tab))
            add(Destination.PageEditWeb.pattern(tab))
        }
        add(Destination.AddAccount.route)
        add(Destination.ShareCapture.ROUTE)
    }
