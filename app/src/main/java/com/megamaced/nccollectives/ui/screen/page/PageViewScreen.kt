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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.megamaced.nccollectives.BuildConfig
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.ui.components.BacklinkChipRow
import com.megamaced.nccollectives.ui.components.ConflictBanner
import com.megamaced.nccollectives.ui.components.ErrorState
import com.megamaced.nccollectives.ui.components.LoadingState
import com.megamaced.nccollectives.ui.components.MarkdownView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageViewScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onEditWeb: () -> Unit,
    onAttachments: () -> Unit,
    onOpenPage: (Long) -> Unit,
    onBrowseTag: (collectiveId: Long, tagName: String) -> Unit,
    viewModel: PageViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val page by viewModel.page.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()
    val imageBaseUrl by viewModel.imageBaseUrl.collectAsState()
    val backlinks by viewModel.backlinks.collectAsState()
    val remoteAttachmentCount by viewModel.remoteAttachmentCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var menuExpanded by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showTrashConfirm by remember { mutableStateOf(false) }
    // Pre-commit undo for trash (Batch 18m). When the user confirms the
    // dialog this flips true; the LaunchedEffect below shows the snackbar
    // and either fires the server delete or quietly cancels.
    //
    // B-37: `rememberSaveable` so a configuration change (rotation, locale,
    // theme) doesn't drop the pending-trash state on the floor. Without
    // this the LaunchedEffect coroutine is cancelled on recomposition and
    // `pendingTrash` resets to false — both the trash and the undo are
    // silently swallowed. With it, the snackbar re-appears after rotation
    // and either commits or undoes as before.
    var pendingTrash by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(ui.statusMessage, ui.errorMessage) {
        val msg = ui.statusMessage ?: ui.errorMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissStatus()
        }
    }

    LaunchedEffect(ui.copiedPageId) {
        val target = ui.copiedPageId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Page duplicated",
            actionLabel = "Open",
            duration = SnackbarDuration.Short,
        )
        viewModel.acknowledgeCopied()
        if (result == SnackbarResult.ActionPerformed) {
            onOpenPage(target)
        }
    }

    LaunchedEffect(pendingTrash) {
        if (!pendingTrash) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Moved to trash",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        pendingTrash = false
        if (result != SnackbarResult.ActionPerformed) {
            // Snackbar dismissed (timeout or other) → commit the trash.
            viewModel.trashPage(onTrashed = onBack)
        }
        // If `ActionPerformed`, the user tapped UNDO — no server call,
        // no navigation. Page-view stays as-is.
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
                                // Batch 28: debug-only entry for the
                                // collaborative WebView editor. Batch 29
                                // will replace this with a user-settable
                                // preference + automatic routing of the
                                // normal Edit button.
                                if (BuildConfig.DEBUG) {
                                    DropdownMenuItem(
                                        text = { Text("Edit (collaborative, debug)") },
                                        onClick = {
                                            menuExpanded = false
                                            onEditWeb()
                                        },
                                    )
                                }
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
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.duplicatePage()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Move to trash") },
                                    onClick = {
                                        menuExpanded = false
                                        showTrashConfirm = true
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
                    backlinks = backlinks,
                    remoteAttachmentCount = remoteAttachmentCount,
                    onReplaceWithDraft = viewModel::replaceWithDraft,
                    onDiscardDraft = viewModel::discardDraft,
                    onOpenPage = onOpenPage,
                    onWikiLink = { target -> viewModel.resolveWikilink(target, onOpenPage) },
                    onBrowseTag = { tagName -> onBrowseTag(currentPage.collectiveId, tagName) },
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
            onCreate = viewModel::createTag,
            onBrowse = { tag ->
                val collectiveId = page?.collectiveId ?: return@TagPickerSheet
                showTagPicker = false
                onBrowseTag(collectiveId, tag.name)
            },
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

    if (showTrashConfirm) {
        val target = page
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTrashConfirm = false },
            title = { Text("Move to trash?") },
            text = {
                Text(
                    text = "\"${target?.title.orEmpty()}\" will be moved to the collective's trash. " +
                        "You can restore it from Trash.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showTrashConfirm = false
                    // Stage the trash — the LaunchedEffect on `pendingTrash`
                    // shows the snackbar and decides whether to commit based
                    // on whether the user taps UNDO.
                    pendingTrash = true
                }) { Text("Move to trash") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showTrashConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageViewContent(
    page: Page,
    body: String,
    imageBaseUrl: String?,
    backlinks: List<Page>,
    remoteAttachmentCount: Int,
    onReplaceWithDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
    onOpenPage: (Long) -> Unit,
    onWikiLink: (String) -> Unit,
    onBrowseTag: (String) -> Unit,
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
        val editedRelative = if (page.serverTimestamp > 0) {
            val span = android.text.format.DateUtils.getRelativeTimeSpanString(
                page.serverTimestamp * 1000L,
                System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS,
                android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
            )
            span.toString()
        } else {
            null
        }
        val subtitle = listOfNotNull(
            page.lastUserDisplayName.takeIf { it.isNotEmpty() }?.let { "Last changed by $it" },
            editedRelative?.let { "· $it" },
        ).joinToString(" ")
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (page.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                page.tags.forEach { tag ->
                    AssistChip(
                        onClick = { onBrowseTag(tag) },
                        label = { Text(tag) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }
        // B-56: `key(remoteAttachmentCount)` rebuilds the MarkdownView (and
        // its underlying TextView + Markwon Spannable) whenever a queued
        // upload promotes to REMOTE. Without this, an image referenced in
        // the body before its upload completes 404s once and the broken
        // slot stays broken — Markwon doesn't retry until setMarkdown is
        // called on a fresh view.
        key(remoteAttachmentCount) {
            MarkdownView(
                markdown = body,
                imageBaseUrl = imageBaseUrl,
                onWikiLink = onWikiLink,
            )
        }
        BacklinkChipRow(pages = backlinks, onOpenPage = onOpenPage)
    }
}
