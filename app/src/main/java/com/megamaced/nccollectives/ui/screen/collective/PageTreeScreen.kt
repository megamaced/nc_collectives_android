package com.megamaced.nccollectives.ui.screen.collective

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.ErrorState
import com.megamaced.nccollectives.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageTreeScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: PageTreeViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val nodes by viewModel.nodes.collectAsState()

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = ui.collectiveName.ifBlank { "Pages" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.padding(scaffoldPadding).fillMaxSize()) {
            when {
                ui.isRefreshing && nodes.isEmpty() -> LoadingState()
                ui.errorMessage != null && nodes.isEmpty() ->
                    ErrorState(message = ui.errorMessage!!, onRetry = viewModel::refresh)
                nodes.isEmpty() ->
                    EmptyState(
                        title = "No pages",
                        message = "This collective doesn't have any pages yet.",
                    )
                else -> PageTreeList(
                    nodes = nodes,
                    expanded = ui.expanded,
                    onToggle = viewModel::toggleExpanded,
                )
            }
        }
    }
}

@Composable
private fun PageTreeList(
    nodes: List<PageNode>,
    expanded: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(nodes, key = { it.page.id }) { node ->
            PageTreeItem(
                node = node,
                isExpanded = node.page.id in expanded,
                onToggle = { onToggle(node.page.id) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PageTreeItem(
    node: PageNode,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val rowModifier =
        if (node.hasChildren) {
            Modifier.fillMaxWidth().clickable(onClick = onToggle)
        } else {
            Modifier.fillMaxWidth()
        }
    Row(
        modifier = rowModifier.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(modifier = Modifier.width((16 + node.depth * 16).dp))
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (node.hasChildren) {
                Icon(
                    imageVector = if (isExpanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = node.page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
            style = MaterialTheme.typography.titleMedium,
        )
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(
                text = node.page.title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (node.page.lastUserDisplayName.isNotEmpty()) {
                Text(
                    text = "Edited by ${node.page.lastUserDisplayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
