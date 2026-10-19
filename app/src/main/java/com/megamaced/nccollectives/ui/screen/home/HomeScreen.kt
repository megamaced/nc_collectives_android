package com.megamaced.nccollectives.ui.screen.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.ui.components.EmptyState
import com.megamaced.nccollectives.ui.components.SnackbarMessageEffect
import com.megamaced.nccollectives.ui.components.rememberNowMillis
import com.megamaced.nccollectives.ui.navigation.OnReselected
import com.megamaced.nccollectives.ui.navigation.scrollToTop
import com.megamaced.nccollectives.ui.screen.FAB_CLEARANCE
import com.megamaced.nccollectives.ui.screen.collective.RecentPagesStrip
import com.megamaced.nccollectives.util.pageEditedLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    innerPadding: PaddingValues,
    onOpenPage: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onSeeAll: (HomeSection, collectiveFilter: Long?) -> Unit,
    onBrowseTag: (String) -> Unit,
    onOpenAlerts: () -> Unit,
    reselected: Flow<Unit> = emptyFlow(),
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val collectives by viewModel.collectives.collectAsStateWithLifecycle()
    val recentFilter by viewModel.recentFilter.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // One ticker for the screen, so the "edited … ago" labels age while it
    // stays open.
    val nowMillis by rememberNowMillis()
    val listState = rememberLazyListState()
    // Labelled at the top of the feed, icon-only once scrolled into it.
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    OnReselected(reselected) { listState.scrollToTop() }
    val editable = remember(collectives) { collectives.filter { it.canEdit } }
    var showNewPage by rememberSaveable { mutableStateOf(false) }

    SnackbarMessageEffect(viewModel.messages, snackbarHostState)
    // A new id each time Home comes on screen: navigating away disposes this
    // composition, so coming back remembers afresh.
    val visit = remember(viewModel) { viewModel.beginVisit() }
    val currentOnOpenPage by rememberUpdatedState(onOpenPage)
    LaunchedEffect(viewModel, visit) {
        viewModel.createdPages.collect { created ->
            // Opened only from the visit that asked for it, and only while
            // the user isn't filling in another New page: either way,
            // opening it now would pull them off what they are doing — a
            // screen they came back for, or a form they haven't sent. Offered
            // instead.
            if (created.visit == visit && !showNewPage) {
                currentOnOpenPage(created.pageId)
            } else {
                launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Created “${created.title}”",
                        actionLabel = "Open",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) currentOnOpenPage(created.pageId)
                }
            }
        }
    }

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
        floatingActionButton = {
            if (editable.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text("New page") },
                    // The label slot is hidden from accessibility services, so the
                    // icon carries the button's name, expanded or not.
                    icon = { Icon(Icons.Filled.Add, contentDescription = "New page") },
                    onClick = {
                        viewModel.startNewPage(editable)
                        showNewPage = true
                    },
                    expanded = fabExpanded,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
    ) { scaffoldPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::pullToRefresh,
            modifier = Modifier.padding(scaffoldPadding).fillMaxSize(),
        ) {
            HomeContent(
                feed = feed,
                collectives = collectives,
                recentFilter = recentFilter,
                nowMillis = nowMillis,
                listState = listState,
                onOpenPage = onOpenPage,
                onSeeAll = { section -> onSeeAll(section, recentFilter.takeIf { section == HomeSection.RecentlyUpdated }) },
                onRecentFilter = viewModel::setRecentFilter,
                onBrowseTag = onBrowseTag,
                onOpenAlerts = onOpenAlerts,
            )
        }
    }

    if (showNewPage) {
        val collectiveId by viewModel.newPageCollectiveId.collectAsStateWithLifecycle()
        val parents by viewModel.newPageParents.collectAsStateWithLifecycle()
        val creating by viewModel.isCreatingPage.collectAsStateWithLifecycle()
        HomeNewPageDialog(
            collectives = editable,
            collectiveId = collectiveId,
            parents = parents,
            busy = creating,
            onSelectCollective = viewModel::selectNewPageCollective,
            onCreate = { targetCollectiveId, parentId, title ->
                // Closed only once the request is taken, so a refused one
                // leaves the user's form where it was.
                if (viewModel.createPage(targetCollectiveId, parentId, title, visit)) showNewPage = false
            },
            onDismiss = { showNewPage = false },
        )
    }
}

/**
 * Home's sections, each hidden while it has nothing to show. [feed] null is
 * the cache not having answered yet, and draws nothing rather than the
 * empty state.
 */
@Composable
internal fun HomeContent(
    feed: HomeFeed?,
    collectives: List<Collective>,
    recentFilter: Long?,
    nowMillis: Long,
    listState: LazyListState,
    onOpenPage: (Long) -> Unit,
    onSeeAll: (HomeSection) -> Unit,
    onRecentFilter: (Long?) -> Unit,
    onBrowseTag: (String) -> Unit,
    onOpenAlerts: () -> Unit,
) {
    if (feed == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    if (feed.isEmpty(recentFiltered = recentFilter != null)) {
        EmptyState(
            title = "Nothing here yet",
            message = "Pages you open, and pages edited in your collectives, will show up here.",
        )
        return
    }
    val labels = remember(collectives) { collectives.associate { it.id to it.label } }
    var showAllTags by rememberSaveable { mutableStateOf(false) }

    fun seeAll(
        section: HomeSection,
        shown: Int,
        limit: Int,
    ): (() -> Unit)? = if (shown > limit) ({ onSeeAll(section) }) else null

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        val attention = feed.attention.rows()
        if (attention.isNotEmpty() || feed.unseenAlerts > 0) {
            item(key = "header-attention") {
                HomeSectionHeader(
                    title = HomeSection.Attention.title,
                    onSeeAll = seeAll(HomeSection.Attention, attention.size, HOME_PREVIEW_ROWS),
                )
            }
            if (feed.unseenAlerts > 0) {
                item(key = "attention-alerts") { UnseenAlertsRow(feed.unseenAlerts, onClick = onOpenAlerts) }
            }
            items(attention.take(HOME_PREVIEW_ROWS), key = { "attention-${it.page.id}" }) { row ->
                HomePageRow(row.page, labels[row.page.collectiveId], row.supporting, onClick = { onOpenPage(row.page.id) })
            }
        }

        if (feed.jumpBackIn.isNotEmpty()) {
            item(key = "jump-back-in") {
                HomeSectionHeader(
                    title = HomeSection.JumpBackIn.title,
                    onSeeAll = seeAll(HomeSection.JumpBackIn, feed.jumpBackIn.size, HOME_PREVIEW_CARDS),
                )
                RecentPagesStrip(
                    pages = feed.jumpBackIn.take(HOME_PREVIEW_CARDS),
                    onPageClick = onOpenPage,
                    title = null,
                    caption = { page -> labels[page.collectiveId] },
                )
            }
        }

        pageSection(
            section = HomeSection.UpdatedSinceRead,
            pages = feed.updatedSinceRead,
            limit = HOME_PREVIEW_ROWS,
            labels = labels,
            nowMillis = nowMillis,
            onSeeAll = seeAll(HomeSection.UpdatedSinceRead, feed.updatedSinceRead.size, HOME_PREVIEW_ROWS),
            onOpenPage = onOpenPage,
        )

        if (feed.recentlyUpdated.isNotEmpty() || recentFilter != null) {
            item(key = "header-recent") {
                HomeSectionHeader(
                    title = HomeSection.RecentlyUpdated.title,
                    onSeeAll = seeAll(HomeSection.RecentlyUpdated, feed.recentlyUpdated.size, HOME_PREVIEW_RECENT),
                )
                if (showsCollectiveFilter(collectives)) {
                    CollectiveFilterChips(collectives, recentFilter, onRecentFilter)
                }
                if (feed.recentlyUpdated.isEmpty()) {
                    Text(
                        text = "Nothing recent in this collective.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            pageRows(HomeSection.RecentlyUpdated, feed.recentlyUpdated.take(HOME_PREVIEW_RECENT), labels, nowMillis, onOpenPage)
        }

        pageSection(
            section = HomeSection.YourEdits,
            pages = feed.yourEdits,
            limit = HOME_PREVIEW_ROWS,
            labels = labels,
            nowMillis = nowMillis,
            onSeeAll = seeAll(HomeSection.YourEdits, feed.yourEdits.size, HOME_PREVIEW_ROWS),
            onOpenPage = onOpenPage,
        )

        if (feed.tags.isNotEmpty()) {
            item(key = "tags") {
                val more = feed.tags.size > HOME_PREVIEW_TAGS
                HomeSectionHeader(
                    title = "Browse by tag",
                    onSeeAll = if (more) ({ showAllTags = !showAllTags }) else null,
                    seeAllLabel = if (showAllTags) "Show fewer" else "Show all",
                )
                TagChips(
                    tags = if (showAllTags) feed.tags else feed.tags.take(HOME_PREVIEW_TAGS),
                    onTag = onBrowseTag,
                )
            }
        }
    }
}

/** A list section's header and its first [limit] rows; nothing when [pages] is empty. */
private fun LazyListScope.pageSection(
    section: HomeSection,
    pages: List<PageListItem>,
    limit: Int,
    labels: Map<Long, String>,
    nowMillis: Long,
    onSeeAll: (() -> Unit)?,
    onOpenPage: (Long) -> Unit,
) {
    if (pages.isEmpty()) return
    item(key = "header-${section.key}") {
        HomeSectionHeader(title = section.title, onSeeAll = onSeeAll)
    }
    pageRows(section, pages.take(limit), labels, nowMillis, onOpenPage)
}

internal fun LazyListScope.pageRows(
    section: HomeSection,
    pages: List<PageListItem>,
    labels: Map<Long, String>,
    nowMillis: Long,
    onOpenPage: (Long) -> Unit,
) {
    items(pages, key = { "${section.key}-${it.id}" }) { page ->
        HomePageRow(
            page = page,
            caption = labels[page.collectiveId],
            supporting = supportingLine(section, page, nowMillis),
            onClick = { onOpenPage(page.id) },
        )
    }
}

/**
 * What a row says happened to its page. Your own edits leave the editor
 * out: in that section it is always you.
 */
internal fun supportingLine(
    section: HomeSection,
    page: PageListItem,
    nowMillis: Long,
): String? =
    when (section) {
        HomeSection.YourEdits -> pageEditedLine("", page.serverTimestamp, nowMillis)
        else -> pageEditedLine(page.lastUserDisplayName, page.serverTimestamp, nowMillis)
    }
