package com.megamaced.nccollectives.ui.screen.collective

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.ErrorState
import com.megamaced.nccollectives.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectiveListScreen(
    innerPadding: PaddingValues,
    onCollectiveClick: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CollectiveListViewModel = hiltViewModel(),
) {
    val collectives by viewModel.collectives.collectAsState()
    val ui by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Collectives",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenFavorites) {
                        Icon(Icons.Outlined.Bookmark, contentDescription = "Favorites")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.padding(scaffoldPadding).fillMaxSize()) {
            when {
                ui.isRefreshing && collectives.isEmpty() -> LoadingState()
                ui.errorMessage != null && collectives.isEmpty() ->
                    ErrorState(
                        message = ui.errorMessage!!,
                        onRetry = viewModel::refresh,
                    )
                collectives.isEmpty() ->
                    EmptyState(
                        title = "No collectives",
                        message = "You don't have access to any collectives yet.",
                    )
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(collectives, key = { it.id }) { collective ->
                            CollectiveRow(
                                collective = collective,
                                onClick = { onCollectiveClick(collective.id) },
                            )
                            HorizontalDivider()
                        }
                    }
            }
        }
    }
}

@Composable
private fun CollectiveRow(
    collective: Collective,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = collective.emoji?.takeIf { it.isNotBlank() } ?: "📓",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Text(
            text = collective.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
