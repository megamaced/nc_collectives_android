package com.megamaced.nccollectives.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.megamaced.nccollectives.ui.screen.alerts.AlertsScreen
import com.megamaced.nccollectives.ui.screen.collective.CollectiveListScreen
import com.megamaced.nccollectives.ui.screen.collective.PageTreeScreen
import com.megamaced.nccollectives.ui.screen.favorites.FavoritesScreen
import com.megamaced.nccollectives.ui.screen.home.HomeScreen
import com.megamaced.nccollectives.ui.screen.login.LoginScreen
import com.megamaced.nccollectives.ui.screen.members.MembersScreen
import com.megamaced.nccollectives.ui.screen.page.AttachmentsScreen
import com.megamaced.nccollectives.ui.screen.page.PageEditScreen
import com.megamaced.nccollectives.ui.screen.page.PageEditWebScreen
import com.megamaced.nccollectives.ui.screen.page.PageViewScreen
import com.megamaced.nccollectives.ui.screen.search.SearchScreen
import com.megamaced.nccollectives.ui.screen.settings.SettingsScreen
import com.megamaced.nccollectives.ui.screen.share.ShareCaptureScreen
import com.megamaced.nccollectives.ui.screen.tag.TagBrowseScreen
import com.megamaced.nccollectives.ui.screen.trash.CollectiveTrashScreen
import com.megamaced.nccollectives.ui.screen.trash.TrashScreen

@Composable
internal fun NcCollectivesNavHost(
    navController: NavHostController,
    innerPadding: PaddingValues,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelTab.Home.graphRoute,
    ) {
        tabGraph(TopLevelTab.Home, navController, innerPadding) {
            composable(TopLevelTab.Home.rootRoute) { entry ->
                HomeScreen(
                    innerPadding = innerPadding,
                    onOpenPage = { pageId ->
                        navController.navigateFrom(entry, Destination.PageView.route(TopLevelTab.Home, pageId))
                    },
                    onOpenSettings = {
                        navController.navigateFrom(entry, Destination.Settings.route) { launchSingleTop = true }
                    },
                )
            }
            composable(Destination.Settings.route) { entry ->
                SettingsScreen(
                    innerPadding = innerPadding,
                    onBack = { navController.popBackStackFrom(entry) },
                    onAddAccount = { navController.navigateFrom(entry, Destination.AddAccount.route) },
                )
            }
            composable(Destination.AddAccount.route) { entry ->
                LoginScreen(
                    innerPadding = innerPadding,
                    onCancel = { navController.popBackStackFrom(entry) },
                )
            }
        }
        tabGraph(TopLevelTab.Collectives, navController, innerPadding) {
            composable(TopLevelTab.Collectives.rootRoute) { entry ->
                val tab = TopLevelTab.Collectives
                ReturnHomeOnBack(navController)
                CollectiveListScreen(
                    innerPadding = innerPadding,
                    onCollectiveClick = { id -> navController.navigateFrom(entry, Destination.PageTree.route(tab, id)) },
                    onOpenPage = { pageId -> navController.navigateFrom(entry, Destination.PageView.route(tab, pageId)) },
                    onOpenTrash = {
                        navController.navigateFrom(entry, Destination.CollectiveTrash.route(tab)) { launchSingleTop = true }
                    },
                )
            }
        }
        tabGraph(TopLevelTab.Search, navController, innerPadding) {
            composable(TopLevelTab.Search.rootRoute) { entry ->
                ReturnHomeOnBack(navController)
                SearchScreen(
                    innerPadding = innerPadding,
                    // Pushed on top of the results, so Back returns to them
                    // with the query intact.
                    onOpenPage = { pageId ->
                        navController.navigateFrom(entry, Destination.PageView.route(TopLevelTab.Search, pageId))
                    },
                )
            }
        }
        tabGraph(TopLevelTab.Favorites, navController, innerPadding) {
            composable(TopLevelTab.Favorites.rootRoute) { entry ->
                ReturnHomeOnBack(navController)
                FavoritesScreen(
                    innerPadding = innerPadding,
                    onOpenPage = { pageId ->
                        navController.navigateFrom(entry, Destination.PageView.route(TopLevelTab.Favorites, pageId))
                    },
                )
            }
        }
        tabGraph(TopLevelTab.Alerts, navController, innerPadding) {
            composable(TopLevelTab.Alerts.rootRoute) {
                ReturnHomeOnBack(navController)
                AlertsScreen(innerPadding = innerPadding)
            }
        }
        composable(Destination.ShareCapture.ROUTE) {
            ShareCaptureScreen(
                innerPadding = innerPadding,
                onDismiss = {
                    if (!navController.popBackStack()) {
                        navController.navigate(TopLevelTab.Home.graphRoute) {
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
    }
}

/**
 * One tab: its root screen and anything [root] adds, plus every detail
 * screen a tab can open, under routes carrying the tab's prefix.
 */
private fun NavGraphBuilder.tabGraph(
    tab: TopLevelTab,
    navController: NavHostController,
    innerPadding: PaddingValues,
    root: NavGraphBuilder.() -> Unit,
) {
    navigation(route = tab.graphRoute, startDestination = tab.rootRoute) {
        root()
        detailDestinations(tab, navController, innerPadding)
    }
}

private fun NavGraphBuilder.detailDestinations(
    tab: TopLevelTab,
    navController: NavHostController,
    innerPadding: PaddingValues,
) {
    composable(
        route = Destination.PageTree.pattern(tab),
        arguments = listOf(
            navArgument(Destination.PageTree.ARG_COLLECTIVE_ID) { type = NavType.LongType },
        ),
    ) { backStackEntry ->
        // `onOpenMembers` takes no argument — the members entry point sits
        // on the collective header, which already knows which collective
        // it is drawing — so the id comes from the route instead, the same
        // way the `PageView` block below reads its page id.
        val collectiveId = checkNotNull(
            backStackEntry.arguments?.getLong(Destination.PageTree.ARG_COLLECTIVE_ID),
        )
        PageTreeScreen(
            innerPadding = innerPadding,
            onBack = { navController.popBackStackFrom(backStackEntry) },
            onPageClick = { pageId -> navController.navigateFrom(backStackEntry, Destination.PageView.route(tab, pageId)) },
            onOpenTrash = { id -> navController.navigateFrom(backStackEntry, Destination.Trash.route(tab, id)) },
            // B-76's `launchSingleTop`, for the same reason: two taps land
            // two copies of the screen on the back stack, and here the
            // second copy also fires a second Circles members request.
            onOpenMembers = {
                navController.navigateFrom(backStackEntry, Destination.Members.route(tab, collectiveId)) {
                    launchSingleTop = true
                }
            },
        )
    }
    composable(
        route = Destination.PageView.pattern(tab),
        arguments = listOf(
            navArgument(Destination.PageView.ARG_PAGE_ID) { type = NavType.LongType },
        ),
    ) { backStackEntry ->
        val pageId = checkNotNull(
            backStackEntry.arguments?.getLong(Destination.PageView.ARG_PAGE_ID),
        )
        // Every callback here is guarded by this entry, and several fire
        // late: the edit route after a capability lookup, a wikilink after
        // a cache lookup, Back after the trash-undo snackbar times out. By
        // then the user may have switched tabs, and an unguarded callback
        // would act on whichever stack is now showing.
        PageViewScreen(
            innerPadding = innerPadding,
            onBack = { navController.popBackStackFrom(backStackEntry) },
            // B-76: `launchSingleTop` — resolving the edit route is async
            // (a server-capability probe on the first call per session), so
            // two taps can both reach here. A duplicate editor entry is bad
            // enough on its own; for the web editor it opens a second
            // `directediting` session on the same page.
            onEdit = {
                navController.navigateFrom(backStackEntry, Destination.PageEdit.route(tab, pageId)) {
                    launchSingleTop = true
                }
            },
            onEditWeb = {
                navController.navigateFrom(backStackEntry, Destination.PageEditWeb.route(tab, pageId)) {
                    launchSingleTop = true
                }
            },
            onAttachments = { navController.navigateFrom(backStackEntry, Destination.Attachments.route(tab, pageId)) },
            onOpenPage = { target ->
                if (target != pageId) navController.navigateFrom(backStackEntry, Destination.PageView.route(tab, target))
            },
            onBrowseTag = { collectiveId, tagName ->
                navController.navigateFrom(backStackEntry, Destination.TagBrowse.route(tab, collectiveId, tagName))
            },
        )
    }
    composable(
        route = Destination.PageEdit.pattern(tab),
        arguments = listOf(
            navArgument(Destination.PageEdit.ARG_PAGE_ID) { type = NavType.LongType },
        ),
    ) { entry ->
        PageEditScreen(
            innerPadding = innerPadding,
            onClose = { navController.popBackStackFrom(entry) },
        )
    }
    composable(
        route = Destination.PageEditWeb.pattern(tab),
        arguments = listOf(
            navArgument(Destination.PageEditWeb.ARG_PAGE_ID) { type = NavType.LongType },
        ),
    ) { entry ->
        PageEditWebScreen(
            innerPadding = innerPadding,
            onClose = { navController.popBackStackFrom(entry) },
        )
    }
    composable(
        route = Destination.Attachments.pattern(tab),
        arguments = listOf(
            navArgument(Destination.Attachments.ARG_PAGE_ID) { type = NavType.LongType },
        ),
    ) { entry ->
        AttachmentsScreen(
            innerPadding = innerPadding,
            onBack = { navController.popBackStackFrom(entry) },
        )
    }
    composable(
        route = Destination.Trash.pattern(tab),
        arguments = listOf(
            navArgument(Destination.Trash.ARG_COLLECTIVE_ID) { type = NavType.LongType },
        ),
    ) { entry ->
        TrashScreen(
            innerPadding = innerPadding,
            onBack = { navController.popBackStackFrom(entry) },
        )
    }
    composable(Destination.CollectiveTrash.route(tab)) { entry ->
        CollectiveTrashScreen(
            innerPadding = innerPadding,
            onBack = { navController.popBackStackFrom(entry) },
        )
    }
    composable(
        route = Destination.Members.pattern(tab),
        arguments = listOf(
            navArgument(Destination.Members.ARG_COLLECTIVE_ID) { type = NavType.LongType },
        ),
    ) { entry ->
        MembersScreen(
            innerPadding = innerPadding,
            onBack = { navController.popBackStackFrom(entry) },
        )
    }
    composable(
        route = Destination.TagBrowse.pattern(tab),
        arguments = listOf(
            navArgument(Destination.TagBrowse.ARG_COLLECTIVE_ID) { type = NavType.LongType },
            navArgument(Destination.TagBrowse.ARG_TAG_NAME) { type = NavType.StringType },
        ),
    ) { entry ->
        TagBrowseScreen(
            innerPadding = innerPadding,
            onBack = { navController.popBackStackFrom(entry) },
            onOpenPage = { pageId ->
                navController.navigateFrom(entry, Destination.PageView.route(tab, pageId))
            },
        )
    }
}

/**
 * Navigate to [route] only if [from] is still the screen in front.
 *
 * Every screen callback goes through this or [popBackStackFrom], because
 * some fire after a delay — an async lookup, a snackbar timing out — when the
 * user may have switched tabs, and acting then would push onto whichever
 * stack is now showing.
 *
 * It also stops a second tap that lands while the first destination is
 * still animating in from pushing a second copy. `launchSingleTop` is not
 * the fix for pages: it matches on the `page/{pageId}` destination, not the
 * page, so it would hand the open page's entry the new id while keeping the
 * ViewModel that already loaded the old one — the screen showing one page
 * and Edit opening another. Once `navigate` has run, the outgoing entry is
 * no longer `RESUMED`, so the second tap stops here.
 */
private fun NavHostController.navigateFrom(
    from: NavBackStackEntry,
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    if (from.lifecycle.currentState != Lifecycle.State.RESUMED) return
    navigate(route, builder)
}

/**
 * Pop [from] only if it is still the top of the back stack, for the same
 * late-callback reason as [navigateFrom].
 *
 * Checked against the current entry rather than `RESUMED`, so a Back tap
 * while the screen is still animating in is not dropped.
 */
private fun NavHostController.popBackStackFrom(from: NavBackStackEntry) {
    if (currentBackStackEntry?.id != from.id) return
    popBackStack()
}
