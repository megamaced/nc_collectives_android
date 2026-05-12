package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PageViewUiState(
    val isLoadingBody: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

@HiltViewModel
class PageViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val pageRepository: PageRepository,
        private val collectiveRepository: CollectiveRepository,
    ) : ViewModel() {
        private val pageId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.PageView.ARG_PAGE_ID),
        )

        val page: StateFlow<Page?> = pageRepository.observePage(pageId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

        val isFavorite: StateFlow<Boolean> = combine(
            page,
            collectiveRepository.observeCollectives(),
        ) { p, collectives ->
            p?.let { current ->
                collectives.firstOrNull { it.id == current.collectiveId }?.favoritePageIds?.contains(current.id) ?: false
            } ?: false
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        private val _uiState = MutableStateFlow(PageViewUiState())
        val uiState: StateFlow<PageViewUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val cached = pageRepository.getPage(pageId)
                if (cached != null && cached.bodyMd == null) refreshBody()
            }
        }

        fun refreshBody() {
            if (_uiState.value.isLoadingBody) return
            _uiState.update { it.copy(isLoadingBody = true, errorMessage = null) }
            viewModelScope.launch {
                val result = pageRepository.fetchBody(pageId)
                _uiState.update { state ->
                    state.copy(
                        isLoadingBody = false,
                        errorMessage = if (result is ApiResult.Success) null else result.userMessage(),
                    )
                }
            }
        }

        fun toggleFavorite() {
            val current = page.value ?: return
            val want = !isFavorite.value
            viewModelScope.launch {
                val result = collectiveRepository.toggleFavorite(
                    collectiveId = current.collectiveId,
                    pageId = current.id,
                    favorite = want,
                )
                if (result !is ApiResult.Success) {
                    _uiState.update { it.copy(statusMessage = result.userMessage()) }
                }
            }
        }

        fun replaceWithDraft() {
            val draft = page.value?.draftBodyMd ?: return
            viewModelScope.launch {
                val outcome = pageRepository.replaceWithDraft(pageId, draft)
                _uiState.update {
                    it.copy(statusMessage = saveOutcomeMessage(outcome))
                }
            }
        }

        fun discardDraft() {
            viewModelScope.launch { pageRepository.discardDraft(pageId) }
        }

        fun dismissStatus() {
            _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
        }

        private fun saveOutcomeMessage(outcome: SaveOutcome): String? =
            when (outcome) {
                SaveOutcome.Saved -> "Page replaced"
                SaveOutcome.Queued -> "Saved offline — will sync when online"
                SaveOutcome.Conflict -> "Still conflicting — try again later"
                is SaveOutcome.Error -> outcome.message
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
