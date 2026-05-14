package com.megamaced.nccollectives.ui.screen.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.ui.components.EmptyState

@Composable
internal fun FavoritesScreen(
    innerPadding: PaddingValues,
    onOpenPage: (Long) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsState()

    if (favorites.isEmpty()) {
        EmptyState(
            title = "No favorites yet",
            message = "Pages you favorite will appear here.",
            modifier = Modifier.padding(innerPadding),
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        items(favorites, key = { it.page.id }) { entry ->
            FavoriteRow(entry = entry, onClick = { onOpenPage(entry.page.id) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun FavoriteRow(
    entry: FavoriteEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = entry.page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
            style = MaterialTheme.typography.titleMedium,
        )
        Column {
            Text(
                text = entry.page.title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = entry.collectiveName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
