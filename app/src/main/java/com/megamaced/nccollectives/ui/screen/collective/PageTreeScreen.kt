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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.ErrorState
import com.megamaced.nccollectives.ui.components.LoadingState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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

    // Prime the landing-page body once we know which page it is, so the
    // landing-card snippet can show a preview without the user opening it.
    LaunchedEffect(ui.landingPage?.id) {
        viewModel.primeLandingBody()
    }

    val isCompactWidth = LocalConfiguration.current.screenWidthDp < 600

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
                    IconButton(onClick = { onOpenTrash(viewModel.collectiveId) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Trash")
                    }
                    IconButton(onClick = { newPageMode = NewPageMode.PickParent }) {
                        Icon(Icons.Filled.Add, contentDescription = "New page")
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
                ui.isRefreshing && nodes.isEmpty() && ui.landingPage == null -> LoadingState()
                ui.errorMessage != null && nodes.isEmpty() && ui.landingPage == null ->
                    ErrorState(message = ui.errorMessage!!, onRetry = viewModel::refresh)
                nodes.isEmpty() && ui.landingPage == null ->
                    EmptyState(
                        title = "No pages",
                        message = "This collective doesn't have any pages yet.",
                    )
                else -> PageTreeList(
                    nodes = nodes,
                    expanded = ui.expanded,
                    landingPage = ui.landingPage,
                    collectiveName = ui.collectiveName,
                    collectiveEmoji = ui.collectiveEmoji,
                    recentPages = ui.recentPages,
                    showLandingCard = isCompactWidth,
                    onToggle = viewModel::toggleExpanded,
                    onPageClick = onPageClick,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onAddSubpage = { parentId -> newPageMode = NewPageMode.FixedParent(parentId) },
                    onReorder = viewModel::onReorderDrop,
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
    landingPage: com.megamaced.nccollectives.domain.model.Page?,
    collectiveName: String,
    collectiveEmoji: String?,
    recentPages: List<com.megamaced.nccollectives.domain.model.Page>,
    showLandingCard: Boolean,
    onToggle: (Long) -> Unit,
    onPageClick: (Long) -> Unit,
    onToggleFavorite: (Long, Boolean) -> Unit,
    onAddSubpage: (Long) -> Unit,
    onReorder: (movedPageId: Long, newVisibleOrder: List<Long>) -> Unit,
) {
    // Local mirror of the upstream tree so the drag animation can swap
    // rows continuously while the drag is in flight (Batch 23). The
    // upstream `nodes` re-emits once the server confirms the new
    // `subpageOrder` (or rolls back on failure) — the `remember(nodes)`
    // key re-seeds the local copy each time that happens.
    var localNodes by remember(nodes) { mutableStateOf(nodes) }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Library passes `LazyListItemInfo`s. Headers carry String keys
        // ("header-recent", …); tree rows carry the page id as Long. We
        // only react when both endpoints are tree rows.
        val fromKey = from.key as? Long ?: return@rememberReorderableLazyListState
        val toKey = to.key as? Long ?: return@rememberReorderableLazyListState
        val fromIdx = localNodes.indexOfFirst { it.page.id == fromKey }
        val toIdx = localNodes.indexOfFirst { it.page.id == toKey }
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return@rememberReorderableLazyListState
        localNodes = localNodes.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (recentPages.isNotEmpty()) {
            item(key = "header-recent") {
                RecentPagesStrip(pages = recentPages, onPageClick = onPageClick)
            }
        }
        if (showLandingCard && landingPage != null) {
            item(key = "header-landing") {
                LandingPageCard(
                    landing = landingPage,
                    collectiveName = collectiveName,
                    collectiveEmoji = collectiveEmoji,
                    onClick = { onPageClick(landingPage.id) },
                )
            }
        }
        if (recentPages.isNotEmpty() || (showLandingCard && landingPage != null)) {
            item(key = "header-divider") { HorizontalDivider() }
        }
        items(localNodes, key = { it.page.id }) { node ->
            ReorderableItem(reorderState, key = node.page.id) { _ ->
                PageTreeItem(
                    node = node,
                    isExpanded = node.page.id in expanded,
                    onToggle = { onToggle(node.page.id) },
                    onOpen = { onPageClick(node.page.id) },
                    onToggleFavorite = { onToggleFavorite(node.page.id, node.isFavorite) },
                    onAddSubpage = { onAddSubpage(node.page.id) },
                    dragHandleModifier = Modifier.longPressDraggableHandle(
                        onDragStopped = {
                            // B-35: pass the moved id + the full post-drag
                            // visible order so the VM works in a single
                            // coordinate space (the new list). The previous
                            // (oldIdx, newIdx) pair mixed pre-drag and
                            // post-drag indices.
                            val newOrder = localNodes.map { it.page.id }
                            if (newOrder != nodes.map { it.page.id }) {
                                onReorder(node.page.id, newOrder)
                            }
                        },
                    ),
                )
            }
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
    dragHandleModifier: Modifier = Modifier,
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
        // Long-press-to-drag handle (Batch 23). Long-press rather than
        // touch-drag so casual scrolls past the icon don't grab a page.
        IconButton(
            onClick = {},
            modifier = dragHandleModifier,
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
