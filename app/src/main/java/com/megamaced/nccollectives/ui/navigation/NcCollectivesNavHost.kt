package com.megamaced.nccollectives.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.megamaced.nccollectives.ui.screen.collective.CollectiveListScreen
import com.megamaced.nccollectives.ui.screen.collective.PageTreeScreen
import com.megamaced.nccollectives.ui.screen.favorites.FavoritesScreen
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
        startDestination = Destination.Collectives.route,
    ) {
        composable(Destination.Collectives.route) {
            CollectiveListScreen(
                innerPadding = innerPadding,
                onCollectiveClick = { id -> navController.navigate(Destination.PageTree.route(id)) },
                onOpenSearch = { navController.navigate(Destination.Search.route) },
                onOpenFavorites = { navController.navigate(Destination.Favorites.route) },
                onOpenSettings = { navController.navigate(Destination.Settings.route) },
                onOpenTrash = { navController.navigate(Destination.CollectiveTrash.route) },
            )
        }
        composable(Destination.Search.route) {
            SearchScreen(
                innerPadding = innerPadding,
                onBack = { navController.popBackStack() },
                onOpenPage = { pageId ->
                    navController.navigate(Destination.PageView.route(pageId)) {
                        popUpTo(Destination.Collectives.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Destination.Favorites.route) {
            FavoritesScreen(
                innerPadding = innerPadding,
                onBack = { navController.popBackStack() },
                onOpenPage = { pageId ->
                    navController.navigate(Destination.PageView.route(pageId)) {
                        popUpTo(Destination.Collectives.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Destination.Settings.route) {
            SettingsScreen(
                innerPadding = innerPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Destination.PageTree.route,
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
                onBack = { navController.popBackStack() },
                onPageClick = { pageId -> navController.navigate(Destination.PageView.route(pageId)) },
                onOpenTrash = { id -> navController.navigate(Destination.Trash.route(id)) },
                onOpenSearch = { navController.navigate(Destination.Search.route) },
                onOpenFavorites = { navController.navigate(Destination.Favorites.route) },
                // B-76's `launchSingleTop`, for the same reason: two taps land
                // two copies of the screen on the back stack, and here the
                // second copy also fires a second Circles members request.
                onOpenMembers = {
                    navController.navigate(Destination.Members.route(collectiveId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(
            route = Destination.PageView.route,
            arguments = listOf(
                navArgument(Destination.PageView.ARG_PAGE_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val pageId = checkNotNull(
                backStackEntry.arguments?.getLong(Destination.PageView.ARG_PAGE_ID),
            )
            PageViewScreen(
                innerPadding = innerPadding,
                onBack = { navController.popBackStack() },
                // B-76: `launchSingleTop` — resolving the edit route is async
                // (a server-capability probe on the first call per session), so
                // two taps can both reach here. A duplicate editor entry is bad
                // enough on its own; for the web editor it opens a second
                // `directediting` session on the same page.
                onEdit = {
                    navController.navigate(Destination.PageEdit.route(pageId)) {
                        launchSingleTop = true
                    }
                },
                onEditWeb = {
                    navController.navigate(Destination.PageEditWeb.route(pageId)) {
                        launchSingleTop = true
                    }
                },
                onAttachments = { navController.navigate(Destination.Attachments.route(pageId)) },
                onOpenPage = { target ->
                    if (target != pageId) navController.navigate(Destination.PageView.route(target))
                },
                onBrowseTag = { collectiveId, tagName ->
                    navController.navigate(Destination.TagBrowse.route(collectiveId, tagName))
                },
            )
        }
        composable(
            route = Destination.PageEdit.route,
            arguments = listOf(
                navArgument(Destination.PageEdit.ARG_PAGE_ID) { type = NavType.LongType },
            ),
        ) {
            PageEditScreen(
                innerPadding = innerPadding,
                onClose = { navController.popBackStack() },
            )
        }
        composable(
            route = Destination.PageEditWeb.route,
            arguments = listOf(
                navArgument(Destination.PageEditWeb.ARG_PAGE_ID) { type = NavType.LongType },
            ),
        ) {
            PageEditWebScreen(
                innerPadding = innerPadding,
                onClose = { navController.popBackStack() },
            )
        }
        composable(
            route = Destination.Attachments.route,
            arguments = listOf(
                navArgument(Destination.Attachments.ARG_PAGE_ID) { type = NavType.LongType },
            ),
        ) {
            AttachmentsScreen(
                innerPadding = innerPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destination.ShareCapture.route) {
            ShareCaptureScreen(
                innerPadding = innerPadding,
                onDismiss = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Destination.Collectives.route) {
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(
            route = Destination.Trash.route,
            arguments = listOf(
                navArgument(Destination.Trash.ARG_COLLECTIVE_ID) { type = NavType.LongType },
            ),
        ) {
            TrashScreen(
                innerPadding = innerPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destination.CollectiveTrash.route) {
            CollectiveTrashScreen(
                innerPadding = innerPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Destination.Members.route,
            arguments = listOf(
                navArgument(Destination.Members.ARG_COLLECTIVE_ID) { type = NavType.LongType },
            ),
        ) {
            MembersScreen(
                innerPadding = innerPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Destination.TagBrowse.route,
            arguments = listOf(
                navArgument(Destination.TagBrowse.ARG_COLLECTIVE_ID) { type = NavType.LongType },
                navArgument(Destination.TagBrowse.ARG_TAG_NAME) { type = NavType.StringType },
            ),
        ) {
            TagBrowseScreen(
                innerPadding = innerPadding,
                onBack = { navController.popBackStack() },
                onOpenPage = { pageId ->
                    navController.navigate(Destination.PageView.route(pageId))
                },
            )
        }
    }
}
