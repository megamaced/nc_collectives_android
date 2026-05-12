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
 * A node in the rendered tree. [depth] is 0 for top-level pages and grows
 * by one per parent.
 */
data class PageNode(
    val page: Page,
    val depth: Int,
    val hasChildren: Boolean,
)

data class PageTreeUiState(
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val collectiveName: String = "",
    val expanded: Set<Long> = emptySet(),
)

@HiltViewModel
class PageTreeViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
    ) : ViewModel() {
        private val collectiveId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.PageTree.ARG_COLLECTIVE_ID),
        )

        private val pagesFlow = pageRepository.observePages(collectiveId)

        private val _uiState = MutableStateFlow(PageTreeUiState())
        val uiState: StateFlow<PageTreeUiState> = _uiState.asStateFlow()

        /** Flat tree projection, recomputed as pages or the expanded set change. */
        val nodes: StateFlow<List<PageNode>> =
            combine(pagesFlow, _uiState.map { it.expanded }) { pages, expanded ->
                buildVisibleNodes(pages, expanded)
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

        private fun buildVisibleNodes(
            pages: List<Page>,
            expanded: Set<Long>,
        ): List<PageNode> {
            // Collectives' page tree: every page has a `parentId`. Top-level
            // pages have `parentId == 0`. Children are sorted by title to
            // keep ordering stable across refreshes.
            val byParent: Map<Long, List<Page>> = pages
                .groupBy { it.parentId }
                .mapValues { (_, list) -> list.sortedBy { it.title.lowercase() } }

            val out = mutableListOf<PageNode>()

            fun walk(
                parent: Long,
                depth: Int,
            ) {
                for (child in byParent[parent].orEmpty()) {
                    val hasChildren = byParent[child.id]?.isNotEmpty() == true
                    out += PageNode(child, depth, hasChildren)
                    if (hasChildren && child.id in expanded) walk(child.id, depth + 1)
                }
            }
            walk(parent = 0L, depth = 0)
            return out
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
