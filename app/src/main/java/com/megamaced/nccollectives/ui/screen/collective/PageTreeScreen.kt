package com.megamaced.nccollectives.ui.screen.collective

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.ErrorState
import com.megamaced.nccollectives.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageTreeScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onPageClick: (Long) -> Unit,
    onOpenTrash: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    viewModel: PageTreeViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val nodes by viewModel.nodes.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // `null` means closed. A `Long` value means "show the new-page dialog with
    // this fixed parent" (per-row subpage). `0L` for the top-bar action opens
    // the dialog with a parent picker.
    var newPageMode by remember { mutableStateOf<NewPageMode?>(null) }

    LaunchedEffect(ui.statusMessage) {
        val msg = ui.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissStatus()
        }
    }

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
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenFavorites) {
                        Icon(Icons.Outlined.Bookmark, contentDescription = "Favorites")
                    }
                    IconButton(onClick = { newPageMode = NewPageMode.PickParent }) {
                        Icon(Icons.Filled.Add, contentDescription = "New page")
                    }
                    IconButton(onClick = { onOpenTrash(viewModel.collectiveId) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Trash")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
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
                    onPageClick = onPageClick,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onAddSubpage = { parentId -> newPageMode = NewPageMode.FixedParent(parentId) },
                )
            }
        }
    }

    val mode = newPageMode
    if (mode != null) {
        NewPageDialog(
            mode = mode,
            parentChoices = ui.parentChoices,
            onCreate = { parentId, title ->
                viewModel.createPage(parentId, title)
                newPageMode = null
            },
            onDismiss = { newPageMode = null },
        )
    }
}

private sealed interface NewPageMode {
    /** Top-bar action — let the user pick a parent. */
    object PickParent : NewPageMode

    /** Per-row "+" action — parent is fixed. */
    data class FixedParent(
        val parentId: Long,
    ) : NewPageMode
}

@Composable
private fun PageTreeList(
    nodes: List<PageNode>,
    expanded: Set<Long>,
    onToggle: (Long) -> Unit,
    onPageClick: (Long) -> Unit,
    onToggleFavorite: (Long, Boolean) -> Unit,
    onAddSubpage: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(nodes, key = { it.page.id }) { node ->
            PageTreeItem(
                node = node,
                isExpanded = node.page.id in expanded,
                onToggle = { onToggle(node.page.id) },
                onOpen = { onPageClick(node.page.id) },
                onToggleFavorite = { onToggleFavorite(node.page.id, node.isFavorite) },
                onAddSubpage = { onAddSubpage(node.page.id) },
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
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddSubpage: () -> Unit,
) {
    // No indentation regardless of depth — the tree relationship is shown by
    // the chevron on folder rows, not by horizontal offset.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.hasChildren) {
            IconButton(onClick = onToggle) {
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
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = node.page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
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
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (node.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (node.isFavorite) "Unfavorite" else "Favorite",
                tint = if (node.isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onAddSubpage) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New subpage",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewPageDialog(
    mode: NewPageMode,
    parentChoices: List<Page>,
    onCreate: (Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var pickedParentId by remember(mode) {
        mutableStateOf(
            when (mode) {
                is NewPageMode.FixedParent -> mode.parentId
                NewPageMode.PickParent -> parentChoices.firstOrNull { it.parentId == 0L }?.id
                    ?: parentChoices.firstOrNull()?.id
            },
        )
    }
    var showParentSheet by remember { mutableStateOf(false) }

    val pickedParent = parentChoices.firstOrNull { it.id == pickedParentId }
    val canCreate = title.isNotBlank() && pickedParentId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mode is NewPageMode.PickParent) {
                    Text("Where should this page go?", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { showParentSheet = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = pickedParent?.emoji?.takeIf { it.isNotBlank() } ?: "📁",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = pickedParent?.title ?: "Pick a parent…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (pickedParent != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("Title") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (canCreate) onCreate(pickedParentId!!, title.trim())
                    }),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = { onCreate(pickedParentId!!, title.trim()) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (showParentSheet) {
        ParentPickerSheet(
            choices = parentChoices,
            onPick = {
                pickedParentId = it.id
                showParentSheet = false
            },
            onDismiss = { showParentSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentPickerSheet(
    choices: List<Page>,
    onPick: (Page) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pick a parent page", style = MaterialTheme.typography.titleMedium)
            if (choices.isEmpty()) {
                Text(
                    text = "No folder pages available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(choices, key = { it.id }) { page ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(page) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = page.emoji?.takeIf { it.isNotBlank() } ?: "📁",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(text = page.title, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider()
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}
