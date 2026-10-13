package com.megamaced.nccollectives.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.sync.FullSync
import com.megamaced.nccollectives.sync.SyncOutcome
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Home tab: what changed recently, across every collective.
 *
 * Reads the Room cache only, so it works offline and costs nothing to open.
 * A pull runs the same full sync as Settings → Sync now, because the pages
 * shown here come from every collective.
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        collectiveRepository: CollectiveRepository,
        pageRepository: PageRepository,
        private val fullSync: FullSync,
    ) : ViewModel() {
        val recentPages: StateFlow<List<PageListItem>> =
            pageRepository.observeRecentPagesAcrossCollectives(RECENT_LIMIT).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

        /** Each collective's emoji and name, for the caption under a page. */
        val collectiveLabels: StateFlow<Map<Long, String>> =
            collectiveRepository
                .observeCollectives()
                .map { collectives ->
                    collectives.associate { collective ->
                        val emoji = collective.emoji?.takeIf { it.isNotBlank() }
                        collective.id to if (emoji != null) "$emoji ${collective.name}" else collective.name
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = emptyMap(),
                )

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

        /** One-shot snackbar messages; see `CollectiveListViewModel.messages` (B-79). */
        private val _messages = Channel<String>(Channel.BUFFERED)
        val messages: Flow<String> = _messages.receiveAsFlow()

        fun pullToRefresh() {
            if (_isRefreshing.value) return
            _isRefreshing.value = true
            viewModelScope.launch {
                val failure = when (val outcome = fullSync.run()) {
                    SyncOutcome.Success -> null
                    SyncOutcome.Unauthorised -> "Session expired — please log in again."
                    is SyncOutcome.Retryable -> outcome.message
                    is SyncOutcome.Failed -> outcome.message
                }
                _isRefreshing.value = false
                failure?.let { _messages.trySend(it) }
            }
        }

        private companion object {
            const val RECENT_LIMIT = 30
        }
    }
