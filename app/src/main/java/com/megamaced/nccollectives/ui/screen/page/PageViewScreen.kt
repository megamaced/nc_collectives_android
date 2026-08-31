package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.ScrollState
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
import androidx.compose.material.icons.automirrored.filled.Toc
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.ui.attachment.openAttachmentExternally
import com.megamaced.nccollectives.ui.components.BacklinkChipRow
import com.megamaced.nccollectives.ui.components.ConflictBanner
import com.megamaced.nccollectives.ui.components.ErrorState
import com.megamaced.nccollectives.ui.components.LoadingState
import com.megamaced.nccollectives.ui.components.MarkdownOutline
import com.megamaced.nccollectives.ui.components.MarkdownView
import com.megamaced.nccollectives.ui.components.SnackbarStatusEffect
import com.megamaced.nccollectives.ui.components.rememberMarkdownOutline
import com.megamaced.nccollectives.util.AttachmentRef
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val context = LocalContext.current
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val imageBaseUrl by viewModel.imageBaseUrl.collectAsStateWithLifecycle()
    val backlinks by viewModel.backlinks.collectAsStateWithLifecycle()
    val remoteAttachmentCount by viewModel.remoteAttachmentCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val editScope = rememberCoroutineScope()

    var menuExpanded by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showTrashConfirm by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    // B-76: disables the Edit button for the duration of the route lookup.
    var resolvingEditRoute by remember { mutableStateOf(false) }

    // Page index (issue #15). The outline is filled in by `MarkdownView` off
    // the rendered `Spanned`, so it only exists once a body has been laid
    // out — hence the heading count gating the toolbar button below.
    val outline = rememberMarkdownOutline()
    val bodyScrollState = rememberScrollState()
    // Content-space y of the rendered body's top edge, i.e. everything the
    // page header (emoji, title, byline, tags, conflict banner) occupies
    // above it. A heading's `Layout` offset is relative to the body view,
    // so this is what turns one into a scroll position.
    var bodyTopPx by remember { mutableIntStateOf(0) }
    val scrollHeadroomPx = with(LocalDensity.current) { OUTLINE_SCROLL_HEADROOM.roundToPx() }

    // Trashing drops the page's Room row, so `page` goes null while the undo
    // snackbar is still up (B-75). Keep rendering the last version we had —
    // swapping the body for a spinner under a "Moved to trash" message reads
    // as a crash.
    var lastLoadedPage by remember { mutableStateOf<Page?>(null) }
    LaunchedEffect(page) {
        page?.let { lastLoadedPage = it }
    }
    val visiblePage = page ?: lastLoadedPage

    // B-81: `statusMessage` only. An `errorMessage` is the full-screen error
    // state's, and this was the one screen that also snackbarred it — which
    // meant its own `dismissStatus` then wiped the error screen out from
    // under the user. See `PageViewModel.refreshBody`.
    SnackbarStatusEffect(ui.statusMessage, snackbarHostState, viewModel::dismissStatus)

    // Attachment staged and ready — fire the view intent, then tell the
    // ViewModel it's been consumed (and whether anything could open it).
    LaunchedEffect(ui.attachmentToOpen) {
        val attachment = ui.attachmentToOpen ?: return@LaunchedEffect
        val opened = openAttachmentExternally(context, attachment)
        viewModel.acknowledgeAttachmentOpened(
            failureMessage = if (opened) null else "No app installed that can open ${attachment.fileName}",
        )
    }

    LaunchedEffect(ui.downloadingAttachment) {
        val name = ui.downloadingAttachment ?: return@LaunchedEffect
        // Indefinite: dismissed implicitly when the download finishes and
        // this effect is cancelled on the state change.
        snackbarHostState.showSnackbar(
            message = "Downloading $name…",
            duration = SnackbarDuration.Indefinite,
        )
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

    // The page is already in the server's trash by the time this runs, so
    // nothing is stranded if the screen is disposed mid-window (B-75) — the
    // user simply loses the undo offer, which matches what they were told.
    // The flag lives in the ViewModel, so a configuration change re-shows the
    // snackbar rather than dropping it, and popping the screen takes the flag
    // with it instead of leaving it armed for the next visit.
    LaunchedEffect(ui.trashedAwaitingUndo) {
        if (!ui.trashedAwaitingUndo) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Moved to trash",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.restoreTrashed()
        } else {
            viewModel.acknowledgeTrashed()
            onBack()
        }
    }

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = visiblePage?.title.orEmpty(),
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
                        IconButton(
                            // B-76: nothing on screen moves while the route is
                            // being resolved, and the first lookup per session
                            // is a network round trip — so a second tap used to
                            // push a second editor destination, which for the
                            // web editor means two `directediting` sessions on
                            // the same page. `launchSingleTop` on both edit
                            // navigations (NcCollectivesNavHost) is the other
                            // half of this.
                            enabled = !resolvingEditRoute,
                            onClick = {
                                // Resolve EditorPreference + server capability,
                                // then route. The decision is async because the
                                // first capability lookup hits the network; once
                                // memoised by DirectEditingRepository the call is
                                // effectively instant.
                                resolvingEditRoute = true
                                editScope.launch {
                                    try {
                                        val decision = viewModel.resolveEditRoute()
                                        decision.fallbackMessage?.let { msg ->
                                            snackbarHostState.showSnackbar(msg)
                                        }
                                        when (decision.route) {
                                            EditRoute.Plain -> onEdit()
                                            EditRoute.Web -> onEditWeb()
                                        }
                                    } finally {
                                        resolvingEditRoute = false
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                        // Only worth a toolbar slot once there is somewhere
                        // to jump to. One heading is an index of one.
                        if (outline.headings.size >= MIN_HEADINGS_FOR_INDEX) {
                            IconButton(onClick = { showOutline = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Toc,
                                    contentDescription = "Page index",
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                // The page already revalidates on open; this is
                                // for the case where it changed while you were
                                // looking at it, and for an answer you can see
                                // ("Page updated" / "Already up to date").
                                DropdownMenuItem(
                                    text = { Text("Refresh") },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.refreshBody()
                                    },
                                )
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
        // The menu still carries "Refresh": pull is a gesture, and a
        // gesture-only refresh is unreachable with TalkBack or a switch
        // device. Both paths land on the same `refreshBody`, so both get
        // the "Page updated" / "Already up to date" snackbar.
        PullToRefreshBox(
            isRefreshing = ui.isRefreshingBody,
            onRefresh = { viewModel.refreshBody() },
            modifier = Modifier.padding(scaffoldPadding).fillMaxSize(),
        ) {
            val currentPage = visiblePage
            when {
                currentPage == null -> {
                    LoadingState()
                }

                currentPage.bodyMd == null && ui.isLoadingBody -> {
                    LoadingState()
                }

                currentPage.bodyMd == null && ui.errorMessage != null -> {
                    ErrorState(message = ui.errorMessage!!, onRetry = viewModel::refreshBody)
                }

                else -> {
                    PageViewContent(
                        page = currentPage,
                        body = currentPage.bodyMd.orEmpty(),
                        imageBaseUrl = imageBaseUrl,
                        backlinks = backlinks,
                        remoteAttachmentCount = remoteAttachmentCount,
                        scrollState = bodyScrollState,
                        outline = outline,
                        onBodyPositioned = { top -> bodyTopPx = top },
                        onReplaceWithDraft = viewModel::replaceWithDraft,
                        onDiscardDraft = viewModel::discardDraft,
                        onOpenPage = onOpenPage,
                        onWikiLink = { target -> viewModel.resolveWikilink(target, onOpenPage) },
                        onAttachmentLink = viewModel::openAttachment,
                        onBrowseTag = { tagName -> onBrowseTag(currentPage.collectiveId, tagName) },
                    )
                }
            }
        }
    }

    if (showOutline) {
        PageOutlineSheet(
            headings = outline.headings,
            onSelect = { heading ->
                showOutline = false
                editScope.launch {
                    // `topOf` is null only if the body has not been laid out,
                    // which cannot happen while its index is on screen — but
                    // fall back to the top rather than assert it.
                    val target = heading
                        ?.let { outline.topOf(it) }
                        ?.let { bodyTopPx + it - scrollHeadroomPx }
                        ?: 0
                    bodyScrollState.animateScrollTo(target.coerceAtLeast(0))
                }
            },
            onDismiss = { showOutline = false },
        )
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
        val target = visiblePage
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
                    // Commits straight away; the "Moved to trash" snackbar
                    // that follows offers a restore (B-75).
                    viewModel.trashPage()
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
    scrollState: ScrollState,
    outline: MarkdownOutline,
    onBodyPositioned: (Int) -> Unit,
    onReplaceWithDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
    onOpenPage: (Long) -> Unit,
    onWikiLink: (String) -> Unit,
    onAttachmentLink: (AttachmentRef) -> Unit,
    onBrowseTag: (String) -> Unit,
) {
    // Captured so the body's offset can be expressed in content space:
    // `positionInWindow` of the body minus that of the viewport gives its
    // on-screen offset, and adding the current scroll makes it absolute.
    // Doing it this way rather than reading `positionInParent` keeps the
    // arithmetic correct however `verticalScroll` chooses to place content.
    var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .onGloballyPositioned { viewportCoordinates = it }
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
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    val viewport = viewportCoordinates ?: return@onGloballyPositioned
                    val top = (
                        coordinates.positionInWindow().y - viewport.positionInWindow().y
                    ).roundToInt() + scrollState.value
                    onBodyPositioned(top)
                },
                imageBaseUrl = imageBaseUrl,
                pageId = page.id,
                onWikiLink = onWikiLink,
                onAttachmentLink = onAttachmentLink,
                outline = outline,
            )
        }
        BacklinkChipRow(pages = backlinks, onOpenPage = onOpenPage)
    }
}

/**
 * Breathing room left above a heading the index scrolled to, so it does not
 * sit flush against the top app bar.
 */
private val OUTLINE_SCROLL_HEADROOM = 12.dp

/** Below this, the index would list fewer places than it costs a tap to open. */
private const val MIN_HEADINGS_FOR_INDEX = 2
