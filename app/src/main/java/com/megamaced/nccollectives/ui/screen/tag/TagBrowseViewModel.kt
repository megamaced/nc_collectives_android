package com.megamaced.nccollectives.ui.screen.tag

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

data class TagBrowseRow(
    val page: Page,
    val isFavorite: Boolean,
)

data class TagBrowseUiState(
    val collectiveName: String = "",
    val tagName: String = "",
    val statusMessage: String? = null,
)

@HiltViewModel
class TagBrowseViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
    ) : ViewModel() {
        val collectiveId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.TagBrowse.ARG_COLLECTIVE_ID),
        )

        // NavType.StringType already URL-decodes the path argument.
        val tagName: String = checkNotNull(
            savedStateHandle.get<String>(Destination.TagBrowse.ARG_TAG_NAME),
        )

        private val _uiState = MutableStateFlow(TagBrowseUiState(tagName = tagName))
        val uiState: StateFlow<TagBrowseUiState> = _uiState.asStateFlow()

        val rows: StateFlow<List<TagBrowseRow>> =
            combine(
                pageRepository.observePagesWithTagInCollective(collectiveId, tagName),
                collectiveRepository
                    .observeCollectives()
                    .map { list -> list.firstOrNull { it.id == collectiveId }?.favoritePageIds.orEmpty() },
            ) { pages, favoriteIds ->
                pages.map { TagBrowseRow(page = it, isFavorite = it.id in favoriteIds) }
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

        fun dismissStatus() {
            _uiState.update { it.copy(statusMessage = null) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
