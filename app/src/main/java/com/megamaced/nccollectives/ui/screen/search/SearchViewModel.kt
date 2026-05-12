package com.megamaced.nccollectives.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.SearchHit
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<SearchHit> = emptyList(),
    val isSearching: Boolean = false,
    val errorMessage: String? = null,
    /** Empty set means "all collectives". */
    val selectedCollectiveIds: Set<Long> = emptySet(),
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: SearchRepository,
        private val collectiveRepository: CollectiveRepository,
        private val userPreferences: UserPreferences,
    ) : ViewModel() {
        val collectives: StateFlow<List<Collective>> = collectiveRepository
            .observeCollectives()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
        private val _uiState = MutableStateFlow(SearchUiState())
        val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

        val recents: StateFlow<List<String>> = userPreferences.flow
            .map { it.recentSearches }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

        private var inFlight: Job? = null

        init {
            _uiState
                .map { it.query }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .onEach(::runSearch)
                .launchIn(viewModelScope)
        }

        fun onQueryChanged(term: String) {
            _uiState.update { it.copy(query = term, errorMessage = null) }
        }

        fun runRecent(term: String) {
            _uiState.update { it.copy(query = term) }
        }

        fun clearRecents() {
            viewModelScope.launch { userPreferences.clearRecentSearches() }
        }

        fun toggleCollectiveFilter(collectiveId: Long) {
            _uiState.update {
                val next = it.selectedCollectiveIds.toMutableSet()
                if (!next.add(collectiveId)) next.remove(collectiveId)
                it.copy(selectedCollectiveIds = next)
            }
        }

        private suspend fun runSearch(term: String) {
            val trimmed = term.trim()
            if (trimmed.length < MIN_QUERY_LENGTH) {
                inFlight?.cancel()
                _uiState.update { it.copy(results = emptyList(), isSearching = false) }
                return
            }
            inFlight?.cancel()
            inFlight = viewModelScope.launch {
                _uiState.update { it.copy(isSearching = true, errorMessage = null) }
                val result = repository.search(trimmed)
                _uiState.update { state ->
                    when (result) {
                        is ApiResult.Success -> state.copy(
                            isSearching = false,
                            results = result.data,
                            errorMessage = null,
                        )
                        else -> state.copy(
                            isSearching = false,
                            results = emptyList(),
                            errorMessage = result.userMessage(),
                        )
                    }
                }
                if (result is ApiResult.Success && result.data.isNotEmpty()) {
                    userPreferences.pushRecentSearch(trimmed)
                }
            }
        }

        private companion object {
            const val DEBOUNCE_MS = 350L
            const val MIN_QUERY_LENGTH = 2
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
