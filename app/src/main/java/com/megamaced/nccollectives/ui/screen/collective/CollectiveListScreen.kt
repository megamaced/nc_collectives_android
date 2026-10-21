package com.megamaced.nccollectives.ui.screen.collective

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.data.prefs.CollectiveSort
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.ListStateSwitch
import com.megamaced.nccollectives.ui.components.SnackbarMessageEffect
import com.megamaced.nccollectives.ui.components.SortHeader
import com.megamaced.nccollectives.ui.navigation.OnReselected
import com.megamaced.nccollectives.ui.navigation.scrollToTop
import com.megamaced.nccollectives.ui.screen.FAB_CLEARANCE
import com.megamaced.nccollectives.ui.screen.alerts.NotifyLevelChooser
import com.megamaced.nccollectives.ui.screen.alerts.NotifyLevelsExplained
import com.megamaced.nccollectives.ui.screen.page.EmojiPickerSheet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectiveListScreen(
    innerPadding: PaddingValues,
    onCollectiveClick: (Long) -> Unit,
    onOpenTrash: () -> Unit,
    reselected: Flow<Unit> = emptyFlow(),
    viewModel: CollectiveListViewModel = hiltViewModel(),
) {
    val collectives by viewModel.collectives.collectAsStateWithLifecycle()
    val collectiveSort by viewModel.collectiveSort.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateSheet by remember { mutableStateOf(false) }
    var emojiTarget by remember { mutableStateOf<Collective?>(null) }
    var pendingTrash by remember { mutableStateOf<Collective?>(null) }
    // An id, not the row: the dialog follows the live row, so a level just
    // chosen shows as chosen.
    var notifyTarget by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    // The FAB keeps its label at the top of the list and shrinks to its icon
    // once the user scrolls into it.
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    OnReselected(reselected) { listState.scrollToTop() }

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
                        text = stringResource(R.string.collectives_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenTrash) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.trash_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.collective_new)) },
                // The label slot is hidden from accessibility services, so the
                // icon carries the button's name, expanded or not.
                icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.collective_new)) },
                onClick = { showCreateSheet = true },
                expanded = fabExpanded,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
    ) { scaffoldPadding ->
        PullToRefreshBox(
            isRefreshing = ui.isRefreshing,
            onRefresh = viewModel::pullToRefresh,
            modifier = Modifier.padding(scaffoldPadding).fillMaxSize(),
        ) {
            ListStateSwitch(
                isLoading = ui.isRefreshing,
                error = ui.errorMessage,
                isEmpty = collectives.isEmpty(),
                onRetry = viewModel::refresh,
                empty = {
                    EmptyState(
                        title = stringResource(R.string.collectives_empty_title),
                        message = stringResource(R.string.collectives_empty_message),
                    )
                },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
                ) {
                    item(key = "header-sort") {
                        SortHeader(
                            title = stringResource(R.string.collectives_title),
                            selected = collectiveSort,
                            options = CollectiveSort.entries,
                            label = ::collectiveSortLabel,
                            onSelect = viewModel::setCollectiveSort,
                        )
                    }
                    items(collectives, key = { it.id }) { collective ->
                        CollectiveRow(
                            collective = collective,
                            onClick = { onCollectiveClick(collective.id) },
                            onSetEmoji = { emojiTarget = collective },
                            onTrash = { pendingTrash = collective },
                            onNotifications = { notifyTarget = collective.id }.takeIf { collective.notifyLevel != null },
                        )
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

    collectives.firstOrNull { it.id == notifyTarget }?.let { target ->
        AlertDialog(
            onDismissRequest = { notifyTarget = null },
            title = { Text(stringResource(R.string.notify_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NotifyLevelsExplained()
                    NotifyLevelChooser(
                        title = target.label,
                        level = target.notifyLevel,
                        onSelect = { viewModel.setNotifyLevel(target.id, it) },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { notifyTarget = null }) { Text(stringResource(R.string.action_done)) } },
        )
    }

    pendingTrash?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingTrash = null },
            title = { Text(stringResource(R.string.collective_trash_confirm_title, target.name)) },
            text = {
                Text(stringResource(R.string.collective_trash_confirm_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.trash(target.id)
                    pendingTrash = null
                }) { Text(stringResource(R.string.action_move_to_trash)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTrash = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Menu labels, worded as a collective's page order menu is so the two read alike. */
@StringRes
internal fun collectiveSortLabel(sort: CollectiveSort): Int =
    when (sort) {
        CollectiveSort.NameAscending -> R.string.sort_name_ascending
        CollectiveSort.NameDescending -> R.string.sort_name_descending
        CollectiveSort.NewestFirst -> R.string.sort_newest_first
        CollectiveSort.OldestFirst -> R.string.sort_oldest_first
    }

@Composable
private fun CollectiveRow(
    collective: Collective,
    onClick: () -> Unit,
    onSetEmoji: () -> Unit,
    onTrash: () -> Unit,
    onNotifications: (() -> Unit)?,
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
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more_options))
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.collective_set_emoji)) },
                    onClick = {
                        menuExpanded = false
                        onSetEmoji()
                    },
                )
                if (onNotifications != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.collective_notifications)) },
                        onClick = {
                            menuExpanded = false
                            onNotifications()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move_to_trash)) },
                    onClick = {
                        menuExpanded = false
                        onTrash()
                    },
                )
            }
        }
    }
}
