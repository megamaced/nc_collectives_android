package com.megamaced.nccollectives.ui.screen.page

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.ui.attachment.openAttachmentExternally
import com.megamaced.nccollectives.ui.components.BacklinkChipRow
import com.megamaced.nccollectives.ui.components.ConflictBanner
import com.megamaced.nccollectives.ui.components.ErrorState
import com.megamaced.nccollectives.ui.components.LoadingState
import com.megamaced.nccollectives.ui.components.MarkdownOutline
import com.megamaced.nccollectives.ui.components.MarkdownView
import com.megamaced.nccollectives.ui.components.SnackbarStatusEffect
import com.megamaced.nccollectives.ui.components.SubpageChipRow
import com.megamaced.nccollectives.ui.components.rememberMarkdownOutline
import com.megamaced.nccollectives.ui.screen.FAB_CLEARANCE
import com.megamaced.nccollectives.util.AttachmentRef
import com.megamaced.nccollectives.util.UiText
import com.megamaced.nccollectives.util.resolve
import com.megamaced.nccollectives.util.uiText
import kotlinx.coroutines.flow.first
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
    val subpages by viewModel.subpages.collectAsStateWithLifecycle()
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

    // Top bar, navigation bar and the Edit label follow the body's scroll.
    val chrome = rememberReadingChrome(bodyScrollState)

    // B-76: the route lookup is async, and the first one per session is a
    // network round trip, so nothing on screen moves while it runs. Guarded
    // here so a second tap can't start a second lookup, and in the nav host by
    // `launchSingleTop` on both editor routes — otherwise a second editor
    // entry, which for the web editor means a second `directediting` session
    // on the same page.
    //
    // [automatic] is the trip into the editor a page takes on opening (see
    // `PageViewModel.openState`), which checks the page again once the route
    // is known: it may have been trashed, or turned up a conflict, meanwhile.
    val resources by rememberUpdatedState(LocalResources.current)

    fun launchEdit(automatic: Boolean) {
        resolvingEditRoute = true
        editScope.launch {
            try {
                // Resolve EditorPreference + server capability, then
                // route. Once memoised by DirectEditingRepository the
                // lookup is effectively instant.
                val decision = viewModel.resolveEditRoute()
                decision.fallbackMessage?.let { msg ->
                    snackbarHostState.showSnackbar(msg.resolve(resources))
                }
                if (automatic && !viewModel.stillOpensStraightIntoEditor()) {
                    viewModel.dropPendingEdit()
                    return@launch
                }
                when (decision.route) {
                    EditRoute.Plain -> onEdit()
                    EditRoute.Web -> onEditWeb()
                }
            } finally {
                resolvingEditRoute = false
            }
        }
    }
    val startEdit: () -> Unit = {
        if (!resolvingEditRoute) {
            viewModel.editTapped()
            launchEdit(automatic = false)
        }
    }
    val launchEditLatest by rememberUpdatedState<(Boolean) -> Unit> { automatic -> launchEdit(automatic) }

    // Trashing drops the page's Room row, so `page` goes null while the undo
    // snackbar is still up (B-75). Keep rendering the last version we had —
    // swapping the body for a spinner under a "Moved to trash" message reads
    // as a crash.
    var lastLoadedPage by remember { mutableStateOf<Page?>(null) }
    LaunchedEffect(page) {
        page?.let { lastLoadedPage = it }
    }
    val visiblePage = page ?: lastLoadedPage

    // Views are recorded while the screen is in front, including a return to
    // it through a saved tab stack. See `PageViewModel.recordViewsWhileShown`.
    //
    // The trip into the editor on opening also waits for the screen to be in
    // front: the nav host drops a navigation from an entry that isn't
    // resumed, as this one isn't while it animates in, and the trip is taken
    // once per visit. A manual Edit already under way takes its place.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.backInFront()
            launch { viewModel.recordViewsWhileShown() }
            viewModel.openState.first { it == PageOpenState.EditPending }
            if (!resolvingEditRoute && viewModel.claimPendingEdit()) {
                launchEditLatest(true)
            } else {
                viewModel.dropPendingEdit()
            }
        }
    }

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
            failureMessage = if (opened) null else uiText(R.string.attachment_no_app, attachment.fileName),
        )
    }

    LaunchedEffect(ui.downloadingAttachment) {
        val name = ui.downloadingAttachment ?: return@LaunchedEffect
        // Indefinite: dismissed implicitly when the download finishes and
        // this effect is cancelled on the state change.
        snackbarHostState.showSnackbar(
            message = resources.getString(R.string.attachment_downloading, name),
            duration = SnackbarDuration.Indefinite,
        )
    }

    LaunchedEffect(ui.copiedPageId) {
        val target = ui.copiedPageId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = resources.getString(R.string.page_duplicated),
            actionLabel = resources.getString(R.string.action_open),
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
            message = resources.getString(R.string.page_moved_to_trash),
            actionLabel = resources.getString(R.string.action_undo),
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
        modifier = Modifier
            .padding(innerPadding)
            .readingChrome(chrome),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = visiblePage?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (page != null) {
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = stringResource(
                                    if (isFavorite) R.string.action_unfavorite else R.string.action_favorite,
                                ),
                            )
                        }
                        // Only worth a toolbar slot once there is somewhere
                        // to jump to. One heading is an index of one.
                        if (outline.headings.size >= MIN_HEADINGS_FOR_INDEX) {
                            IconButton(onClick = { showOutline = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Toc,
                                    contentDescription = stringResource(R.string.page_index),
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more_options))
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
                                    text = { Text(stringResource(R.string.action_refresh)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.refreshBody()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.page_attachments_menu)) },
                                    onClick = {
                                        menuExpanded = false
                                        onAttachments()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.collective_set_emoji)) },
                                    onClick = {
                                        menuExpanded = false
                                        showEmojiPicker = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.page_tags_menu)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.loadAvailableTags()
                                        showTagPicker = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.page_rename_menu)) },
                                    onClick = {
                                        menuExpanded = false
                                        showRenameDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.page_move_menu)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.loadMoveTargets()
                                        showMoveSheet = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.page_duplicate)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.duplicatePage()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_move_to_trash)) },
                                    onClick = {
                                        menuExpanded = false
                                        showTrashConfirm = true
                                    },
                                )
                            }
                        }
                    }
                },
                // Transparent scrolled too: the body is laid out below the
                // bar, never under it, so the scrolled tint would only mark
                // this one bar out from every other screen's.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = chrome.topBarScrollBehavior,
            )
        },
        floatingActionButton = {
            // Gated on the live row, like the actions above: during the
            // trash-undo window the page is gone and there is nothing to edit.
            if (page != null) {
                EditFab(
                    expanded = !chrome.scrollingForward || bodyScrollState.value == 0,
                    busy = resolvingEditRoute,
                    onClick = startEdit,
                )
            }
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
                        subpages = subpages,
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
                    // A scroll made in code doesn't pass through nested
                    // scrolling, so the chrome would stay as it was. Show it,
                    // so a heading always lands just under a visible top bar.
                    chrome.reveal()
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
            title = { Text(stringResource(R.string.page_trash_confirm_title)) },
            text = {
                Text(stringResource(R.string.page_trash_confirm_message, target?.title.orEmpty()))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showTrashConfirm = false
                    // Commits straight away; the "Moved to trash" snackbar
                    // that follows offers a restore (B-75).
                    viewModel.trashPage()
                }) { Text(stringResource(R.string.action_move_to_trash)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showTrashConfirm = false },
                ) { Text(stringResource(R.string.action_cancel)) }
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
    subpages: List<PageListItem>,
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
    // The scrolled content's own coordinates: read *inside* `verticalScroll`,
    // so they move with the scroll, and the body's position relative to them
    // is its content-space offset whatever the scroll is. (Relative to the
    // viewport outside the scroll, the same body moves by every pixel
    // scrolled.)
    var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .onGloballyPositioned { contentCoordinates = it }
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
        val subtitle = lastChangedLine(page.lastUserDisplayName.takeIf { it.isNotEmpty() }, editedRelative)?.resolve().orEmpty()
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
        // Above the body rather than with the backlinks at the bottom: on a
        // hub page the children are often why it was opened, and a long body
        // would push them a screen or more out of reach.
        SubpageChipRow(pages = subpages, onOpenPage = onOpenPage)
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
                    val content = contentCoordinates ?: return@onGloballyPositioned
                    onBodyPositioned(content.localPositionOf(coordinates, Offset.Zero).y.roundToInt())
                },
                imageBaseUrl = imageBaseUrl,
                pageId = page.id,
                onWikiLink = onWikiLink,
                onAttachmentLink = onAttachmentLink,
                outline = outline,
            )
        }
        BacklinkChipRow(pages = backlinks, onOpenPage = onOpenPage)
        Spacer(modifier = Modifier.height(FAB_CLEARANCE))
    }
}

/**
 * Edit, as the screen's floating action button. Labelled while the user is
 * at the top or scrolling back, icon-only while reading forward.
 *
 * Material 3 1.4's extended FAB has no `enabled` parameter, so the busy state
 * is drawn here: a spinner in place of the icon while the editor route is
 * resolved, reported to accessibility services as disabled. The caller
 * ignores taps meanwhile. The name sits on the button itself because the
 * label slot is hidden from accessibility services.
 */
@Composable
private fun EditFab(
    expanded: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val editLabel = stringResource(R.string.action_edit)
    ExtendedFloatingActionButton(
        text = { Text(editLabel) },
        icon = {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            } else {
                Icon(Icons.Filled.Edit, contentDescription = null)
            }
        },
        onClick = onClick,
        expanded = expanded,
        modifier = Modifier.semantics {
            contentDescription = editLabel
            if (busy) disabled()
        },
    )
}

/**
 * Breathing room left above a heading the index scrolled to, so it does not
 * sit flush against the top app bar.
 */
private val OUTLINE_SCROLL_HEADROOM = 12.dp

/** Below this, the index would list fewer places than it costs a tap to open. */
private const val MIN_HEADINGS_FOR_INDEX = 2

/** Who last changed the page and when, as much as is known; null when neither is. */
internal fun lastChangedLine(
    editor: String?,
    relativeTime: String?,
): UiText? =
    when {
        editor != null && relativeTime != null -> uiText(R.string.page_last_changed_by_when, editor, relativeTime)
        editor != null -> uiText(R.string.page_last_changed_by, editor)
        relativeTime != null -> uiText(R.string.page_last_changed_when, relativeTime)
        else -> null
    }
