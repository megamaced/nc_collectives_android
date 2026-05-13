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
import com.megamaced.nccollectives.ui.screen.page.PageEditScreen
import com.megamaced.nccollectives.ui.screen.page.PageViewScreen
import com.megamaced.nccollectives.ui.screen.search.SearchScreen
import com.megamaced.nccollectives.ui.screen.settings.SettingsScreen

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
            )
        }
        composable(Destination.Search.route) {
            SearchScreen(
                innerPadding = innerPadding,
                onOpenPage = { pageId -> navController.navigate(Destination.PageView.route(pageId)) },
            )
        }
        composable(Destination.Favorites.route) { FavoritesScreen(innerPadding) }
        composable(Destination.Settings.route) { SettingsScreen(innerPadding) }
        composable(
            route = Destination.PageTree.route,
            arguments = listOf(
                navArgument(Destination.PageTree.ARG_COLLECTIVE_ID) { type = NavType.LongType },
            ),
        ) {
            PageTreeScreen(
                innerPadding = innerPadding,
                onBack = { navController.popBackStack() },
                onPageClick = { pageId -> navController.navigate(Destination.PageView.route(pageId)) },
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
                onEdit = { navController.navigate(Destination.PageEdit.route(pageId)) },
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
    }
}
