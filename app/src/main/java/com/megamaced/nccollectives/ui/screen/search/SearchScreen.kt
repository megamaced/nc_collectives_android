package com.megamaced.nccollectives.ui.screen.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.domain.model.SearchHit
import com.megamaced.nccollectives.ui.components.EmptyState

@Composable
internal fun SearchScreen(
    innerPadding: PaddingValues,
    onOpenPage: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val recents by viewModel.recents.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
    ) {
        OutlinedTextField(
            value = ui.query,
            onValueChange = viewModel::onQueryChanged,
            label = { Text("Search pages") },
            placeholder = { Text("Title or content") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search,
                capitalization = KeyboardCapitalization.None,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        if (ui.query.isBlank() && recents.isNotEmpty()) {
            RecentsSection(
                recents = recents,
                onRecentClick = viewModel::runRecent,
                onClear = viewModel::clearRecents,
            )
        }

        when {
            ui.isSearching -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            ui.errorMessage != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ui.errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
            }
            ui.results.isEmpty() && ui.query.isNotBlank() ->
                EmptyState(title = "No matches", message = "Nothing matches \"${ui.query}\".")
            ui.results.isNotEmpty() ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(ui.results, key = { it.pageId ?: it.title.hashCode().toLong() }) { hit ->
                        SearchHitRow(hit = hit, onClick = { hit.pageId?.let(onOpenPage) })
                        HorizontalDivider()
                    }
                }
        }
    }
}

@Composable
private fun RecentsSection(
    recents: List<String>,
    onRecentClick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Recent",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClear) { Text("Clear") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            recents.forEach { term ->
                AssistChip(
                    onClick = { onRecentClick(term) },
                    label = { Text(term) },
                )
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    hit: SearchHit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hit.pageId != null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = hit.title, style = MaterialTheme.typography.titleMedium)
        hit.snippet?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (hit.pageId == null) {
            Text(
                text = "Not in your local cache — open the matching collective first.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
