package com.megamaced.nccollectives.ui.screen.collective

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.CollectiveMember
import com.megamaced.nccollectives.domain.model.CollectiveMemberLevel
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.domain.repository.observeFavoritePageIds
import com.megamaced.nccollectives.ui.navigation.Destination
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
import com.megamaced.nccollectives.ui.screen.isRetryableFailure
import com.megamaced.nccollectives.ui.screen.onFailureMessage
import com.megamaced.nccollectives.util.shouldAutoRefresh
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A node in the rendered tree. [hasChildren] toggles the chevron; depth is
 * intentionally omitted — the tree is rendered flat (no indentation).
 *
 * R-54: a [PageListItem], not a [Page]. A tree row draws an emoji, a title
 * and an editor name; carrying the detail model meant every row of every
 * emission also carried whatever markdown was cached for that page.
 */
data class PageNode(
    val page: PageListItem,
    val hasChildren: Boolean,
    val isFavorite: Boolean,
)

/**
 * Everything the landing-page members strip draws, in one place so the
 * screen takes a single object rather than seven more `PageTreeUiState`
 * fields.
 *
 * The three-way split of [isLoading] / [errorMessage] / [members] is the
 * point of the type. Upstream's `MembersWidget.vue` has one signal —
 * `loading = trimmedMembers.length === 0` — so it cannot tell "still
 * fetching" from "fetched nothing" from "not allowed to fetch", and renders
 * a skeleton forever for the last two. Conflating them again here is the
 * one change to avoid.
 *
 * Nothing in here is cached: `CollectiveRepository.listMembers` writes no
 * Room row, so this object *is* the snapshot, and it lives exactly as long
 * as the ViewModel.
 */
data class MembersStripState(
    /** True only while a fetch is in flight. */
    val isLoading: Boolean = false,
    /** User-facing text for a failed fetch; null when nothing has failed. */
    val errorMessage: String? = null,
    /**
     * Whether the failure in [errorMessage] may be tried again. False for
     * every terminal arm, 403 above all — see [isRetryableFailure].
     */
    val canRetry: Boolean = false,
    val members: List<CollectiveMember> = emptyList(),
    /**
     * Whether membership can be addressed at all — the collective has a
     * `circleId`. False hides the strip outright. *Not* a permission check:
     * only the server knows whether this user may read the list.
     */
    val addressable: Boolean = false,
    /**
     * `Collective.userShowMembers`, the web app's per-user display hint,
     * used as the strip's initial expanded state. A hint, never a
     * permission.
     */
    val showInitially: Boolean = true,
    /**
     * Whether to label the trailing action "Manage members" instead of
     * "Show members" (upstream's `level >= 8`). Both open the same screen,
     * so a wrong guess costs a word, not access.
     */
    val canManage: Boolean = false,
)

data class PageTreeUiState(
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val collectiveName: String = "",
    val collectiveEmoji: String? = null,
    val expanded: Set<Long> = emptySet(),
    val statusMessage: String? = null,
    /** Pages eligible as the parent for a new top-level "Add page" — folder pages and the landing page. */
    val parentChoices: List<PageListItem> = emptyList(),
    /**
     * The collective's landing page (parentId == 0). Null until pages load.
     *
     * R-56: the one full [Page] on this screen, from its own single-row
     * flow — [LandingPageCard] renders a snippet of the body, so it needs
     * markdown that no other row here carries.
     */
    val landingPage: Page? = null,
    /** Most-recently-edited pages in this collective for the recent-pages strip. */
    val recentPages: List<PageListItem> = emptyList(),
    /** Circle membership for the landing-page members strip. */
    val membersStrip: MembersStripState = MembersStripState(),
)

@HiltViewModel
class PageTreeViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
    ) : ViewModel() {
        val collectiveId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.PageTree.ARG_COLLECTIVE_ID),
        )

        private val pagesFlow = pageRepository.observePageList(collectiveId)
        private val favoritesFlow = collectiveRepository.observeFavoritePageIds(collectiveId)

        private val _uiState = MutableStateFlow(PageTreeUiState())
        val uiState: StateFlow<PageTreeUiState> = _uiState.asStateFlow()

        /**
         * When the last refresh started, seeded by the `init` refresh so the
         * screen's first resume doesn't immediately repeat it.
         */
        private var lastRefreshAt = 0L

        /**
         * The `circleId` whose members have already been requested. The
         * fetch-once guard behind [requestMembers] — see B-86/B-87 there for
         * why "once" is a correctness requirement and not an optimisation.
         */
        private var membersRequestedFor: String? = null

        val nodes: StateFlow<List<PageNode>> =
            combine(
                pagesFlow,
                // R-43: `_uiState` is written for every unrelated field —
                // isRefreshing on/off, each statusMessage set + clear,
                // recentPages and parentChoices arriving — and each write
                // re-emits the same `expanded` set. Without this filter
                // `buildVisibleNodes` (groupBy + associateBy + recursive
                // walk) re-runs 6+ times per screen entry on a large
                // collective for no change in output.
                _uiState.map { it.expanded }.distinctUntilChanged(),
                favoritesFlow,
            ) { pages, expanded, favoriteIds ->
                buildVisibleNodes(pages, expanded, favoriteIds)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

        init {
            viewModelScope.launch {
                collectiveRepository
                    .observeCollectives()
                    // B-87: `observeCollectives()` is a Room flow over the
                    // whole list, so it re-emits when *any* collective
                    // changes — every favorite toggle on this screen, every
                    // emoji edit, every list refresh. Narrowing to this
                    // collective and de-duplicating is what keeps the
                    // members fetch below from re-firing on all of that;
                    // for a 403 that would be a throttle against the user's
                    // own IP, triggered by their own starring of a page.
                    .mapNotNull { collectives -> collectives.firstOrNull { it.id == collectiveId } }
                    .distinctUntilChanged()
                    .collect { collective -> onCollectiveLoaded(collective) }
            }
            viewModelScope.launch {
                pagesFlow.collect { pages ->
                    // Every page is a valid parent — the server promotes a
                    // leaf parent to a folder when it gains a child (Batch
                    // 18h / 18i OCS migration). R-44: `pagesFlow` already
                    // arrives in title order from the DAO, so this is a
                    // near-sorted re-sort — kept (rather than dropped) only
                    // to hold the ordering the comparator defines; see
                    // [PAGE_TITLE_ORDER].
                    val choices = pages.sortedWith(PAGE_TITLE_ORDER)
                    _uiState.update { it.copy(parentChoices = choices) }
                }
            }
            viewModelScope.launch {
                // R-56: the landing page comes from its own single-row flow
                // rather than being picked out of the list. The list no
                // longer carries a body, and the landing card needs one —
                // this is the whole reason the split is affordable.
                pageRepository.observeLandingPage(collectiveId).collect { landing ->
                    _uiState.update { it.copy(landingPage = landing) }
                }
            }
            viewModelScope.launch {
                pageRepository.observeRecentPages(collectiveId, RECENT_LIMIT).collect { recent ->
                    _uiState.update { it.copy(recentPages = recent) }
                }
            }
            refresh()
        }

        /**
         * Fire-and-forget fetch of the landing page's body so the snippet
         * card can show a preview. No-ops if the body is already cached or
         * the network is unavailable.
         */
        fun primeLandingBody() {
            val landing = _uiState.value.landingPage ?: return
            if (!landing.bodyMd.isNullOrBlank()) return
            viewModelScope.launch { pageRepository.fetchBody(landing.id) }
        }

        /**
         * Fold the collective's own metadata into the state, and take the
         * one shot at its member list.
         *
         * This is the natural trigger point: `circleId`, `level` and
         * `userShowMembers` arrive on the same row as the name and emoji, so
         * the strip is configured and the fetch is started from a single
         * emission rather than from a second observer that would have to
         * re-derive which collective this screen is showing.
         */
        private fun onCollectiveLoaded(collective: Collective) {
            _uiState.update { state ->
                state.copy(
                    collectiveName = collective.name,
                    collectiveEmoji = collective.emoji,
                    membersStrip = state.membersStrip.copy(
                        addressable = collective.circleId != null,
                        showInitially = collective.userShowMembers,
                        canManage = collective.userLevel >= CollectiveMemberLevel.Admin,
                    ),
                )
            }
            requestMembers(collective.circleId)
        }

        /**
         * Fetch the member list once per circle, ever.
         *
         * **B-86: the 403 is the whole design here.** Every Circles
         * controller carries `#[BruteForceProtection]` and calls
         * `throttle()` when a permission check fails, and 403 is the
         * *expected* answer for a non-member — so a members read that sits
         * in anything loop-shaped throttles the user's own IP for using the
         * app normally. Three separate things keep it out of one:
         *  - this guard, so a re-emission of the collective row can't
         *    re-request (B-87),
         *  - [refresh] deliberately not touching members, so
         *    pull-to-refresh and the `refreshIfStale` on every screen
         *    resume can't either,
         *  - [retryMembers] refusing anything [isRetryableFailure] calls
         *    terminal, which is every arm except a request that never
         *    reached the server.
         *
         * Null [circleId] never reaches the repository: it is not "no
         * permission", it is an older server that never said which Team
         * backs this collective, and the strip is hidden rather than shown
         * failing.
         */
        private fun requestMembers(circleId: String?) {
            if (circleId == null) return
            if (membersRequestedFor == circleId) return
            membersRequestedFor = circleId
            fetchMembers(circleId)
        }

        /**
         * Re-run a members fetch the user asked to retry. A no-op unless the
         * failure was classified retryable, so there is no code path from
         * the UI back to a 403.
         */
        fun retryMembers() {
            if (!_uiState.value.membersStrip.canRetry) return
            val circleId = membersRequestedFor ?: return
            fetchMembers(circleId)
        }

        private fun fetchMembers(circleId: String) {
            if (_uiState.value.membersStrip.isLoading) return
            _uiState.update { state ->
                state.copy(
                    membersStrip = state.membersStrip.copy(
                        isLoading = true,
                        errorMessage = null,
                        canRetry = false,
                    ),
                )
            }
            viewModelScope.launch {
                val result = collectiveRepository.listMembers(circleId, MEMBERS_STRIP_LIMIT)
                _uiState.update { state ->
                    state.copy(
                        membersStrip = state.membersStrip.copy(
                            isLoading = false,
                            // A failed retry keeps the avatars it had, the
                            // same way `RemoteListViewModel.refresh` keeps
                            // its rows: the error arm only shows when there
                            // is nothing behind it.
                            members = if (result is ApiResult.Success) result.data else state.membersStrip.members,
                            errorMessage = result.userMessage(),
                            canRetry = isRetryableFailure(result),
                        ),
                    )
                }
            }
        }

        /**
         * B-58: see [CollectiveListViewModel.refreshIfStale]. Same problem,
         * same fix — a page tree left on the backstack while the user reads a
         * page never re-checked the server on the way back.
         */
        fun refreshIfStale() {
            if (!shouldAutoRefresh(lastRefreshAt, System.currentTimeMillis())) return
            refresh()
        }

        /**
         * Re-list pages. Deliberately does **not** re-fetch members: this is
         * what pull-to-refresh and [refreshIfStale] call, so putting a
         * Circles read here is exactly the retry loop B-86 exists to
         * prevent.
         */
        fun refresh() {
            if (_uiState.value.isRefreshing) return
            lastRefreshAt = System.currentTimeMillis()
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            viewModelScope.launch {
                val result = pageRepository.refresh(collectiveId)
                _uiState.update { it.copy(isRefreshing = false, errorMessage = result.userMessage()) }
            }
        }

        fun toggleExpanded(pageId: Long) {
            _uiState.update {
                val next = it.expanded.toMutableSet()
                if (!next.add(pageId)) next.remove(pageId)
                it.copy(expanded = next)
            }
        }

        fun toggleFavorite(
            pageId: Long,
            currentlyFavorite: Boolean,
        ) {
            viewModelScope.launch {
                val result = collectiveRepository.toggleFavorite(
                    collectiveId = collectiveId,
                    pageId = pageId,
                    favorite = !currentlyFavorite,
                )
                result.onFailureMessage { message ->
                    _uiState.update { it.copy(statusMessage = message) }
                }
            }
        }

        fun createPage(
            parentPageId: Long,
            title: String,
        ) {
            val cleaned = title.trim()
            if (cleaned.isEmpty()) return
            viewModelScope.launch {
                val result = pageRepository.createPage(
                    collectiveId = collectiveId,
                    parentPageId = parentPageId,
                    title = cleaned,
                    body = "",
                )
                _uiState.update {
                    it.copy(
                        statusMessage = if (result is ApiResult.Success) "Page created" else result.userMessage(),
                        // Auto-expand the parent so the new page is visible.
                        expanded = if (result is ApiResult.Success) it.expanded + parentPageId else it.expanded,
                    )
                }
            }
        }

        fun dismissStatus() {
            _uiState.update { it.copy(statusMessage = null) }
        }

        /**
         * Commit a drag-to-reorder (Batch 23). [movedPageId] is the page the
         * user dragged; [newVisibleOrder] is the page-id sequence of the
         * tree in its post-drag visible order. Sibling ordering is derived
         * by filtering [newVisibleOrder] to entries sharing the moved
         * page's parent — so dragging across an unrelated subtree still
         * produces a defensible sibling-only reorder.
         *
         * B-35: the previous signature took `fromVisibleIndex` from the
         * upstream `nodes` flow and `toVisibleIndex` from the post-drag
         * `localNodes` mirror — two different coordinate spaces. Passing
         * the new visible order directly removes the mismatch.
         */
        fun onReorderDrop(
            movedPageId: Long,
            newVisibleOrder: List<Long>,
        ) {
            val snapshot = nodes.value
            val moved = snapshot.firstOrNull { it.page.id == movedPageId }?.page ?: return
            val parentId = moved.parentId

            val byId = snapshot.associateBy { it.page.id }
            val newSiblingIds = newVisibleOrder
                .mapNotNull { byId[it]?.page }
                .filter { it.parentId == parentId }
                .map { it.id }
            if (newSiblingIds.size <= 1) return

            val oldSiblingIds = snapshot
                .map { it.page }
                .filter { it.parentId == parentId }
                .map { it.id }
            if (newSiblingIds == oldSiblingIds) return

            viewModelScope.launch {
                val result = pageRepository.setSubpageOrder(
                    collectiveId = collectiveId,
                    parentPageId = parentId,
                    subpageOrderIds = newSiblingIds,
                )
                result.onFailureMessage { message ->
                    _uiState.update { it.copy(statusMessage = message) }
                }
            }
        }

        private companion object {
            const val RECENT_LIMIT = 8

            /**
             * Members fetched for the strip. [MAX_STRIP_AVATARS], not
             * `DEFAULT_MEMBER_LIMIT`: the strip shows no count and no "+N"
             * badge, so nothing past the 15th avatar can change a pixel of
             * it, and at ~2.2 KB per member the default 100 would pull
             * ~220 KB on every landing-page open to draw at most 15 circles.
             * The members *screen* is where the full list belongs.
             */
            const val MEMBERS_STRIP_LIMIT = MAX_STRIP_AVATARS
        }
    }

/**
 * Case-insensitive title order for sibling and parent-choice lists.
 *
 * R-44: the previous `sortedBy { it.title.lowercase() }` ran its selector
 * on *every comparison*, allocating a lowercased copy of each title
 * O(n log n) times per call — and this ran on every tree flatten. A shared
 * comparator allocates nothing.
 *
 * Deliberately *not* removed in favour of the DAO's `ORDER BY title
 * COLLATE NOCASE ASC`: `COLLATE NOCASE` folds ASCII only, so leaning on it
 * would silently reorder non-ASCII titles, and [buildVisibleNodes] is a
 * pure function that must not assume its input arrived pre-sorted. On the
 * already-sorted input it does get, the sort is near-linear.
 */
private val PAGE_TITLE_ORDER: Comparator<PageListItem> =
    compareBy(String.CASE_INSENSITIVE_ORDER) { page: PageListItem -> page.title }

/**
 * Flattens the page list into the depth-first sequence of visible tree
 * rows: the children of the collective's landing page (the landing page's
 * own row is the card above the tree, Batch 21), recursing into expanded
 * folders. Sibling order honours each parent's server-supplied
 * `subpageOrder` first (Batch 23), then alphabetical-by-title for anything
 * not listed.
 *
 * Pure (no ViewModel state) so the flattening + its dedup guard are
 * unit-testable without a ViewModel — see `PageTreeNodesTest`.
 *
 * **Dedup / cycle guard (issue #2 — "crashes with many pages").** Every
 * emitted `page.id` becomes a `LazyColumn` item key in [PageTreeScreen],
 * and Compose throws `IllegalArgumentException` on a duplicate key. A
 * parent's `subpageOrder` is server-supplied and can contain a duplicate
 * id — a large, actively-reorganised collective is the reported trigger —
 * which would otherwise emit the same page twice and crash the screen.
 * The `seen` set makes each id emit at most once and, as a bonus, stops
 * any pathological parent cycle from recursing forever;
 * `orderHint.distinct()` removes the duplicate at the source as well.
 * Neither guard changes output for well-formed data (a page has exactly
 * one `parentId`, so it is reached once).
 */
internal fun buildVisibleNodes(
    pages: List<PageListItem>,
    expanded: Set<Long>,
    favoriteIds: Set<Long>,
): List<PageNode> {
    val byParent: Map<Long, List<PageListItem>> = pages.groupBy { it.parentId }
    val byId: Map<Long, PageListItem> = pages.associateBy { it.id }

    fun siblingsOrdered(parentId: Long): List<PageListItem> {
        val children = byParent[parentId].orEmpty()
        if (children.isEmpty()) return children
        val orderHint = byId[parentId]?.subpageOrder.orEmpty().distinct()
        if (orderHint.isEmpty()) {
            return children.sortedWith(PAGE_TITLE_ORDER)
        }
        val byChildId = children.associateBy { it.id }
        val hinted = orderHint.mapNotNull { byChildId[it] }
        val hintedIds = hinted.map { it.id }.toSet()
        val rest = children
            .filter { it.id !in hintedIds }
            .sortedWith(PAGE_TITLE_ORDER)
        return hinted + rest
    }

    val out = mutableListOf<PageNode>()
    val seen = HashSet<Long>()

    fun walk(parent: Long) {
        for (child in siblingsOrdered(parent)) {
            // Skip an id we've already emitted — a duplicate/cyclic
            // server `subpageOrder` must never yield a duplicate
            // LazyColumn key (issue #2).
            if (!seen.add(child.id)) continue
            val hasChildren = byParent[child.id]?.isNotEmpty() == true
            out += PageNode(
                page = child,
                hasChildren = hasChildren,
                isFavorite = child.id in favoriteIds,
            )
            if (hasChildren && child.id in expanded) walk(child.id)
        }
    }

    // The collective's landing page (parentId == 0) is represented by the
    // landing-card above the tree (Batch 21); skip it here and render its
    // children as the tree's top level.
    val landingPageId = byParent[0L]?.firstOrNull()?.id
    if (landingPageId != null) walk(parent = landingPageId)
    return out
}
