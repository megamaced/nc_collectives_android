package com.megamaced.nccollectives.ui.screen.collective

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.canHoldChildren
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
    val expanded: Set<Long> = emptySet(),
    val statusMessage: String? = null,
    /** Pages eligible as the parent for a new top-level "Add page" — folder pages and the landing page. */
    val parentChoices: List<Page> = emptyList(),
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
                        _uiState.update { it.copy(collectiveName = c.name) }
                    }
                }
            }
            viewModelScope.launch {
                pagesFlow.collect { pages ->
                    val choices = pages
                        .filter { it.canHoldChildren() }
                        .sortedBy { it.title.lowercase() }
                    _uiState.update { it.copy(parentChoices = choices) }
                }
            }
            refresh()
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

        private fun buildVisibleNodes(
            pages: List<Page>,
            expanded: Set<Long>,
            favoriteIds: Set<Long>,
        ): List<PageNode> {
            val byParent: Map<Long, List<Page>> = pages
                .groupBy { it.parentId }
                .mapValues { (_, list) -> list.sortedBy { it.title.lowercase() } }

            val out = mutableListOf<PageNode>()

            fun walk(parent: Long) {
                for (child in byParent[parent].orEmpty()) {
                    val hasChildren = byParent[child.id]?.isNotEmpty() == true
                    out += PageNode(
                        page = child,
                        hasChildren = hasChildren,
                        isFavorite = child.id in favoriteIds,
                    )
                    if (hasChildren && child.id in expanded) walk(child.id)
                }
            }
            walk(parent = 0L)
            return out
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
