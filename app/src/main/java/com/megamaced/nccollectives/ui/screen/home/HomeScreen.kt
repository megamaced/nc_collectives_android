package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.SnackbarMessageEffect
import com.megamaced.nccollectives.ui.components.rememberNowMillis
import com.megamaced.nccollectives.util.pageEditedLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    innerPadding: PaddingValues,
    onOpenPage: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val recentPages by viewModel.recentPages.collectAsStateWithLifecycle()
    val collectiveLabels by viewModel.collectiveLabels.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // One ticker for the list, so the "edited … ago" labels age while the
    // screen stays open.
    val nowMillis by rememberNowMillis()

    SnackbarMessageEffect(viewModel.messages, snackbarHostState)

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Home", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
    ) { scaffoldPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::pullToRefresh,
            modifier = Modifier.padding(scaffoldPadding).fillMaxSize(),
        ) {
            if (recentPages.isEmpty()) {
                EmptyState(
                    title = "Nothing here yet",
                    message = "Pages edited in your collectives will show up here.",
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "header-recent") {
                        Text(
                            text = "Recently updated",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(recentPages, key = { it.id }) { page ->
                        RecentPageRow(
                            page = page,
                            collectiveLabel = collectiveLabels[page.collectiveId],
                            editedLine = pageEditedLine(page.lastUserDisplayName, page.serverTimestamp, nowMillis),
                            onClick = { onOpenPage(page.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentPageRow(
    page: PageListItem,
    collectiveLabel: String?,
    editedLine: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
            style = MaterialTheme.typography.titleMedium,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(collectiveLabel, editedLine).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
