package com.megamaced.nccollectives.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.megamaced.nccollectives.ui.screen.PlaceholderScreen

@Composable
internal fun SettingsScreen(innerPadding: PaddingValues) {
    PlaceholderScreen(
        title = "Settings",
        message = "Account, theme, and sync preferences will appear here.",
        innerPadding = innerPadding,
    )
}
