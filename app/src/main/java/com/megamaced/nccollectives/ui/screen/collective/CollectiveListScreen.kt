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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.ListStateSwitch
import com.megamaced.nccollectives.ui.components.SnackbarMessageEffect
import com.megamaced.nccollectives.ui.screen.page.EmojiPickerSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectiveListScreen(
    innerPadding: PaddingValues,
    onCollectiveClick: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    viewModel: CollectiveListViewModel = hiltViewModel(),
) {
    val collectives by viewModel.collectives.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateSheet by remember { mutableStateOf(false) }
    var emojiTarget by remember { mutableStateOf<Collective?>(null) }
    var pendingTrash by remember { mutableStateOf<Collective?>(null) }

    // A channel rather than a state field: the create-and-navigate path
    // disposes this composition while the snackbar is still up, so a message
    // parked in state would never be cleared and would re-show on the way
    // back (B-79).
    SnackbarMessageEffect(viewModel.messages, snackbarHostState)

    // Re-check the server whenever this screen comes back to the front —
    // returning from a collective reuses the ViewModel, so nothing else
    // would (B-58). Throttled in the ViewModel.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
    }

    // Auto-dismiss the create sheet + navigate into the new collective.
    LaunchedEffect(ui.createdCollectiveId) {
        val id = ui.createdCollectiveId
        if (id != null) {
            showCreateSheet = false
            viewModel.acknowledgeCreated()
            onCollectiveClick(id)
        }
    }

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
                    IconButton(onClick = onOpenTrash) {
                        Icon(Icons.Filled.Delete, contentDescription = "Trash")
                    }
                    IconButton(onClick = { showCreateSheet = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "New collective")
                    }
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
            isRefreshing = ui.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(scaffoldPadding).fillMaxSize(),
        ) {
            ListStateSwitch(
                isLoading = ui.isRefreshing,
                error = ui.errorMessage,
                isEmpty = collectives.isEmpty(),
                onRetry = viewModel::refresh,
                empty = {
                    EmptyState(
                        title = "No collectives",
                        message = "Tap the + button to create one.",
                    )
                },
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(collectives, key = { it.id }) { collective ->
                        CollectiveRow(
                            collective = collective,
                            onClick = { onCollectiveClick(collective.id) },
                            onSetEmoji = { emojiTarget = collective },
                            onTrash = { pendingTrash = collective },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        CreateCollectiveSheet(
            isCreating = ui.isCreating,
            onCreate = viewModel::createCollective,
            onDismiss = { if (!ui.isCreating) showCreateSheet = false },
        )
    }

    emojiTarget?.let { target ->
        EmojiPickerSheet(
            onPick = { emoji ->
                viewModel.setEmoji(target.id, emoji)
                emojiTarget = null
            },
            onDismiss = { emojiTarget = null },
        )
    }

    pendingTrash?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingTrash = null },
            title = { Text("Move \"${target.name}\" to trash?") },
            text = {
                Text(
                    "The collective and all its pages will be moved to the collectives trash. " +
                        "You can restore it from there until you delete it permanently.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.trash(target.id)
                    pendingTrash = null
                }) { Text("Move to trash") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTrash = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CollectiveRow(
    collective: Collective,
    onClick: () -> Unit,
    onSetEmoji: () -> Unit,
    onTrash: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
            modifier = Modifier
                .padding(start = 4.dp)
                .weight(1f),
        )
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Set emoji…") },
                    onClick = {
                        menuExpanded = false
                        onSetEmoji()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Move to trash") },
                    onClick = {
                        menuExpanded = false
                        onTrash()
                    },
                )
            }
        }
    }
}
