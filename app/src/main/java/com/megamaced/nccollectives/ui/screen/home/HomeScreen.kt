package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.megamaced.nccollectives.ui.screen.PlaceholderScreen

@Composable
internal fun HomeScreen(innerPadding: PaddingValues) {
    PlaceholderScreen(
        title = "Home",
        message = "Favorites and recently edited pages will appear here.",
        innerPadding = innerPadding,
    )
}
