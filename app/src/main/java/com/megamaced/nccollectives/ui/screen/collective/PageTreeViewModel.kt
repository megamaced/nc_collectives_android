package com.megamaced.nccollectives.ui.screen.collective

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A node in the rendered tree. [hasChildren] toggles the chevron; depth is
 * intentionally omitted — the tree is rendered flat (no indentation).
 */
data class PageNode(
    val page: Page,
    val hasChildren: Boolean,
    val isFavorite: Boolean,
)

data class PageTreeUiState(
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val collectiveName: String = "",
    val collectiveEmoji: String? = null,
    val expanded: Set<Long> = emptySet(),
    val statusMessage: String? = null,
    /** Pages eligible as the parent for a new top-level "Add page" — folder pages and the landing page. */
    val parentChoices: List<Page> = emptyList(),
    /** The collective's landing page (parentId == 0). Null until pages load. */
    val landingPage: Page? = null,
    /** Most-recently-edited pages in this collective for the recent-pages strip. */
    val recentPages: List<Page> = emptyList(),
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

        private val pagesFlow = pageRepository.observePages(collectiveId)
        private val favoritesFlow = collectiveRepository
            .observeCollectives()
            .map { list -> list.firstOrNull { it.id == collectiveId }?.favoritePageIds.orEmpty() }

        private val _uiState = MutableStateFlow(PageTreeUiState())
        val uiState: StateFlow<PageTreeUiState> = _uiState.asStateFlow()

        val nodes: StateFlow<List<PageNode>> =
            combine(
                pagesFlow,
                _uiState.map { it.expanded },
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
                collectiveRepository.observeCollectives().collect { collectives ->
                    collectives.firstOrNull { it.id == collectiveId }?.let { c ->
                        _uiState.update { it.copy(collectiveName = c.name, collectiveEmoji = c.emoji) }
                    }
                }
            }
            viewModelScope.launch {
                pagesFlow.collect { pages ->
                    // Every page is a valid parent — the server promotes a
                    // leaf parent to a folder when it gains a child (Batch
                    // 18h / 18i OCS migration).
                    val choices = pages.sortedBy { it.title.lowercase() }
                    val landing = pages.firstOrNull { it.parentId == 0L }
                    _uiState.update { it.copy(parentChoices = choices, landingPage = landing) }
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

        fun refresh() {
            if (_uiState.value.isRefreshing) return
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            viewModelScope.launch {
                val result = pageRepository.refresh(collectiveId)
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = if (result is ApiResult.Success) null else result.userMessage(),
                    )
                }
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
                if (result !is ApiResult.Success) {
                    _uiState.update { it.copy(statusMessage = result.userMessage()) }
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
         * Commit a drag-to-reorder (Batch 23). [fromVisibleIndex] /
         * [toVisibleIndex] are indices into the latest [nodes] snapshot.
         * Reordering is scoped to siblings — if the user dropped the row
         * next to a non-sibling, we re-base the move within the moved
         * page's parent group, so the visible drop position still maps
         * onto a sane sibling-only ordering.
         */
        fun onReorderDrop(
            fromVisibleIndex: Int,
            toVisibleIndex: Int,
        ) {
            val snapshot = nodes.value
            if (fromVisibleIndex !in snapshot.indices || toVisibleIndex !in snapshot.indices) return
            if (fromVisibleIndex == toVisibleIndex) return

            val moved = snapshot[fromVisibleIndex].page
            val parentId = moved.parentId
            // The siblings currently visible in the tree, in the order
            // the user sees them. (A sibling whose subtree is collapsed
            // still has one visible row — itself.)
            val visibleSiblings = snapshot
                .map { it.page }
                .filter { it.parentId == parentId }
            if (visibleSiblings.size <= 1) return

            val fromSiblingIndex = visibleSiblings.indexOfFirst { it.id == moved.id }
            if (fromSiblingIndex < 0) return

            // Snap the drop position onto the sibling list. We walk
            // outward from [toVisibleIndex] to find the nearest visible
            // sibling and slot in next to it. This means dragging across
            // an unrelated subtree still produces a defensible
            // sibling-only reorder.
            val toSiblingIndex = nearestSiblingIndex(snapshot, parentId, toVisibleIndex, fromVisibleIndex)
                ?: return

            val newSiblings = visibleSiblings.toMutableList().apply {
                add(toSiblingIndex, removeAt(fromSiblingIndex))
            }
            val newIds = newSiblings.map { it.id }
            if (newIds == visibleSiblings.map { it.id }) return

            viewModelScope.launch {
                val result = pageRepository.setSubpageOrder(
                    collectiveId = collectiveId,
                    parentPageId = parentId,
                    subpageOrderIds = newIds,
                )
                if (result !is ApiResult.Success) {
                    _uiState.update { it.copy(statusMessage = result.userMessage()) }
                }
            }
        }

        /**
         * Walk outward from [centerIndex] looking for the nearest visible
         * page that shares [parentId] (skipping [skipIndex], which is the
         * row being dragged). Returns the sibling-list index where the
         * moved row should land, or null if no peer is visible.
         */
        private fun nearestSiblingIndex(
            snapshot: List<PageNode>,
            parentId: Long,
            centerIndex: Int,
            skipIndex: Int,
        ): Int? {
            for (offset in 0..snapshot.size) {
                listOf(centerIndex + offset, centerIndex - offset).forEach { probe ->
                    if (probe == skipIndex) return@forEach
                    if (probe !in snapshot.indices) return@forEach
                    val candidate = snapshot[probe].page
                    if (candidate.parentId == parentId) {
                        // Map back to the sibling-only list. After
                        // removing the dragged row, the candidate's
                        // position becomes the drop slot.
                        val siblingsWithoutMoved = snapshot
                            .map { it.page }
                            .filter { it.parentId == parentId && it.id != snapshot[skipIndex].page.id }
                        val siblingIndex = siblingsWithoutMoved.indexOfFirst { it.id == candidate.id }
                        if (siblingIndex < 0) return null
                        // Decide before or after: a probe greater than the
                        // skipIndex means the user dragged downward past
                        // this sibling — slot after it. Lower probe → slot
                        // before.
                        return if (probe > skipIndex) siblingIndex + 1 else siblingIndex
                    }
                }
                if (centerIndex + offset > snapshot.size && centerIndex - offset < 0) break
            }
            return null
        }

        private fun buildVisibleNodes(
            pages: List<Page>,
            expanded: Set<Long>,
            favoriteIds: Set<Long>,
        ): List<PageNode> {
            val byParent: Map<Long, List<Page>> = pages.groupBy { it.parentId }
            val byId: Map<Long, Page> = pages.associateBy { it.id }

            // Sibling ordering: parent's explicit `subpageOrder` wins for
            // items it lists (Batch 23 — drag-to-reorder writes this), then
            // anything not in the list falls back to alphabetical-by-title.
            // Matches what the Nextcloud web client does for the "By order"
            // page-order user setting.
            fun siblingsOrdered(parentId: Long): List<Page> {
                val children = byParent[parentId].orEmpty()
                if (children.isEmpty()) return children
                val parent = byId[parentId]
                val orderHint = parent?.subpageOrder.orEmpty()
                if (orderHint.isEmpty()) {
                    return children.sortedBy { it.title.lowercase() }
                }
                val byChildId = children.associateBy { it.id }
                val hinted = orderHint.mapNotNull { byChildId[it] }
                val hintedIds = hinted.map { it.id }.toSet()
                val rest = children
                    .filter { it.id !in hintedIds }
                    .sortedBy { it.title.lowercase() }
                return hinted + rest
            }

            val out = mutableListOf<PageNode>()

            fun walk(parent: Long) {
                for (child in siblingsOrdered(parent)) {
                    val hasChildren = byParent[child.id]?.isNotEmpty() == true
                    out += PageNode(
                        page = child,
                        hasChildren = hasChildren,
                        isFavorite = child.id in favoriteIds,
                    )
                    if (hasChildren && child.id in expanded) walk(child.id)
                }
            }
            // The collective's landing page (parentId == 0) is represented by
            // the landing-card above the tree (Batch 21); skip it here and
            // render its children as the tree's top level.
            val landingPageId = byParent[0L]?.firstOrNull()?.id
            if (landingPageId != null) walk(parent = landingPageId)
            return out
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val RECENT_LIMIT = 8
        }
    }
