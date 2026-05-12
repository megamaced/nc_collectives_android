package com.megamaced.nccollectives.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isRefreshing: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val collectiveRepository: CollectiveRepository,
        private val pageDao: PageDao,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HomeUiState())
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        val collectives: StateFlow<List<Collective>> = collectiveRepository
            .observeCollectives()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        /** Favorite pages across every collective the user belongs to. */
        val favorites: StateFlow<List<Page>> = collectives
            .flatMapLatest { list ->
                val ids = list.flatMap { it.favoritePageIds }.distinct()
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    pageDao.observeByIds(ids).map { rows -> rows.map { it.toDomain() } }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        /** Top 10 recently-edited pages across all collectives. */
        val recent: StateFlow<List<Page>> = pageDao
            .observeRecent(RECENT_LIMIT)
            .map { rows -> rows.map { it.toDomain() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        init {
            refresh()
        }

        fun refresh() {
            if (_uiState.value.isRefreshing) return
            _uiState.update { it.copy(isRefreshing = true) }
            viewModelScope.launch {
                collectiveRepository.refresh()
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val RECENT_LIMIT = 10
        }
    }
