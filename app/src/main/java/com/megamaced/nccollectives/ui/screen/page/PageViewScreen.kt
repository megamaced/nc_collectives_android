package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.ui.components.ConflictBanner
import com.megamaced.nccollectives.ui.components.ErrorState
import com.megamaced.nccollectives.ui.components.LoadingState
import com.megamaced.nccollectives.ui.components.MarkdownView
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageViewScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAttachments: () -> Unit,
    viewModel: PageViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val page by viewModel.page.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()
    val imageBaseUrl by viewModel.imageBaseUrl.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var menuExpanded by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }

    LaunchedEffect(ui.statusMessage, ui.errorMessage) {
        val msg = ui.statusMessage ?: ui.errorMessage
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
                        text = page?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (page != null) {
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                            )
                        }
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Attachments…") },
                                    onClick = {
                                        menuExpanded = false
                                        onAttachments()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Set emoji…") },
                                    onClick = {
                                        menuExpanded = false
                                        showEmojiPicker = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Tags…") },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.loadAvailableTags()
                                        showTagPicker = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename…") },
                                    onClick = {
                                        menuExpanded = false
                                        showRenameDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Move…") },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.loadMoveTargets()
                                        showMoveSheet = true
                                    },
                                )
                            }
                        }
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
            val currentPage = page
            when {
                currentPage == null -> LoadingState()
                currentPage.bodyMd == null && ui.isLoadingBody -> LoadingState()
                currentPage.bodyMd == null && ui.errorMessage != null ->
                    ErrorState(message = ui.errorMessage!!, onRetry = viewModel::refreshBody)
                else -> PageViewContent(
                    page = currentPage,
                    body = currentPage.bodyMd.orEmpty(),
                    imageBaseUrl = imageBaseUrl,
                    onReplaceWithDraft = viewModel::replaceWithDraft,
                    onDiscardDraft = viewModel::discardDraft,
                )
            }
        }
    }

    if (showEmojiPicker) {
        EmojiPickerSheet(
            onPick = { emoji ->
                viewModel.setEmoji(emoji)
                showEmojiPicker = false
            },
            onDismiss = { showEmojiPicker = false },
        )
    }

    if (showTagPicker) {
        TagPickerSheet(
            available = ui.availableTags,
            selectedTagNames = page?.tags?.toSet().orEmpty(),
            isLoading = ui.isLoadingTags,
            onToggle = viewModel::togglePageTag,
            onDismiss = { showTagPicker = false },
        )
    }

    if (showRenameDialog) {
        RenameDialog(
            currentTitle = page?.title.orEmpty(),
            onRename = {
                viewModel.renamePage(it)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showMoveSheet) {
        MovePageSheet(
            targets = ui.movableTargets,
            onPick = {
                viewModel.movePage(it.id)
                showMoveSheet = false
            },
            onDismiss = { showMoveSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageViewContent(
    page: Page,
    body: String,
    imageBaseUrl: String?,
    onReplaceWithDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        page.draftBodyMd?.let { draft ->
            ConflictBanner(
                draft = draft,
                onReplace = onReplaceWithDraft,
                onDiscard = onDiscardDraft,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = page.emoji?.takeIf { it.isNotBlank() } ?: "📄",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        val edited = if (page.serverTimestamp > 0) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(page.serverTimestamp * 1000L))
        } else {
            null
        }
        val subtitle = listOfNotNull(
            page.lastUserDisplayName.takeIf { it.isNotEmpty() }?.let { "Edited by $it" },
            edited?.let { "on $it" },
        ).joinToString(" ")
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (page.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                page.tags.forEach { tag ->
                    AssistChip(
                        onClick = {},
                        label = { Text(tag) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }
        MarkdownView(markdown = body, imageBaseUrl = imageBaseUrl)
    }
}
