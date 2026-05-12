package com.megamaced.nccollectives.ui.screen.collective

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.megamaced.nccollectives.ui.screen.PlaceholderScreen

@Composable
internal fun CollectiveListScreen(innerPadding: PaddingValues) {
    PlaceholderScreen(
        title = "Collectives",
        message = "The list of collectives and nested page trees will appear here.",
        innerPadding = innerPadding,
    )
}
