package com.megamaced.nccollectives.ui.screen.collective

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.ErrorState
import com.megamaced.nccollectives.ui.components.LoadingState

@Composable
internal fun CollectiveListScreen(
    innerPadding: PaddingValues,
    onCollectiveClick: (Long) -> Unit,
    viewModel: CollectiveListViewModel = hiltViewModel(),
) {
    val collectives by viewModel.collectives.collectAsState()
    val ui by viewModel.uiState.collectAsState()

    when {
        ui.isRefreshing && collectives.isEmpty() -> LoadingState(modifier = Modifier.padding(innerPadding))
        ui.errorMessage != null && collectives.isEmpty() ->
            ErrorState(
                message = ui.errorMessage!!,
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(innerPadding),
            )
        collectives.isEmpty() ->
            EmptyState(
                title = "No collectives",
                message = "You don't have access to any collectives yet.",
                modifier = Modifier.padding(innerPadding),
            )
        else ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
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

@Composable
private fun CollectiveRow(
    collective: Collective,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
