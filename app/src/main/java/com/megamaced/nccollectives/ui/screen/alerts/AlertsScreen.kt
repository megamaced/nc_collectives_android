package com.megamaced.nccollectives.ui.screen.alerts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.megamaced.nccollectives.ui.components.EmptyState

/** The Alerts tab. Lists nothing yet: the notifications feed is not wired up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlertsScreen(innerPadding: PaddingValues) {
    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Alerts", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.padding(scaffoldPadding).fillMaxSize()) {
            EmptyState(
                title = "No alerts",
                message = "Mentions and changes to pages you follow will appear here.",
            )
        }
    }
}
