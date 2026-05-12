package com.megamaced.nccollectives.ui.screen.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.megamaced.nccollectives.ui.screen.PlaceholderScreen

@Composable
internal fun SearchScreen(innerPadding: PaddingValues) {
    PlaceholderScreen(
        title = "Search",
        message = "Full-text search across your collectives will appear here.",
        innerPadding = innerPadding,
    )
}
