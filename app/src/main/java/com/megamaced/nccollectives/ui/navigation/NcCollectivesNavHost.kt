package com.megamaced.nccollectives.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.megamaced.nccollectives.ui.screen.collective.CollectiveListScreen
import com.megamaced.nccollectives.ui.screen.favorites.FavoritesScreen
import com.megamaced.nccollectives.ui.screen.home.HomeScreen
import com.megamaced.nccollectives.ui.screen.search.SearchScreen
import com.megamaced.nccollectives.ui.screen.settings.SettingsScreen

@Composable
internal fun NcCollectivesNavHost(
    navController: NavHostController,
    innerPadding: PaddingValues,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.Home.route,
    ) {
        composable(Destination.Home.route) { HomeScreen(innerPadding) }
        composable(Destination.Collectives.route) { CollectiveListScreen(innerPadding) }
        composable(Destination.Search.route) { SearchScreen(innerPadding) }
        composable(Destination.Favorites.route) { FavoritesScreen(innerPadding) }
        composable(Destination.Settings.route) { SettingsScreen(innerPadding) }
    }
}
