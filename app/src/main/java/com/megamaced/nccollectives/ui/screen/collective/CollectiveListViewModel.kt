package com.megamaced.nccollectives.ui.screen.collective

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.util.shouldAutoRefresh
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectiveListUiState(
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val isCreating: Boolean = false,
    /**
     * Set to the new collective id after a successful create so the UI
     * can navigate straight into the page tree. Cleared by the caller
     * via [acknowledgeCreated].
     */
    val createdCollectiveId: Long? = null,
)

@HiltViewModel
class CollectiveListViewModel
    @Inject
    constructor(
        private val repository: CollectiveRepository,
    ) : ViewModel() {
        val collectives: StateFlow<List<Collective>> =
            repository.observeCollectives().stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

        private val _uiState = MutableStateFlow(CollectiveListUiState())
        val uiState: StateFlow<CollectiveListUiState> = _uiState.asStateFlow()

        /**
         * One-shot user messages, deliberately *not* part of [uiState].
         *
         * B-79: a `statusMessage` field can't work on this screen. Creating a
         * collective sets the message and `createdCollectiveId` in the same
         * update, and the navigation that follows disposes the composition
         * while `showSnackbar` is still suspended — so the `dismissStatus()`
         * after it never ran, and the message sat in this (surviving) ViewModel
         * until the user pressed Back, when "Collective created" popped up
         * again apropos of nothing. A channel message is consumed on receipt:
         * shown once, or not at all.
         */
        private val _messages = Channel<String>(Channel.BUFFERED)
        val messages: Flow<String> = _messages.receiveAsFlow()

        /**
         * When the last refresh *started*. Seeded by the `init` refresh so
         * the first `LifecycleResumeEffect` — which fires immediately after
         * the screen appears — doesn't turn every entry into two requests.
         */
        private var lastRefreshAt = 0L

        init {
            refresh()
        }

        /**
         * B-58: called from the screen's resume hook. Navigating back to a
         * screen that's still on the nav backstack reuses this ViewModel, so
         * `init` doesn't run again and nothing else re-checked the server —
         * the list just sat there showing whatever it had when it was first
         * created, which is what the issue-5 reporter kept hitting.
         */
        fun refreshIfStale() {
            if (!shouldAutoRefresh(lastRefreshAt, System.currentTimeMillis())) return
            refresh()
        }

        fun refresh() {
            if (_uiState.value.isRefreshing) return
            lastRefreshAt = System.currentTimeMillis()
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            viewModelScope.launch {
                val result = repository.refresh()
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = if (result is ApiResult.Success) null else result.userMessage(),
                    )
                }
            }
        }

        fun dismissError() {
            _uiState.update { it.copy(errorMessage = null) }
        }

        /** Queue a snackbar message; nulls (a result with nothing to say) are dropped. */
        private fun postMessage(message: String?) {
            if (message == null) return
            _messages.trySend(message)
        }

        fun acknowledgeCreated() {
            _uiState.update { it.copy(createdCollectiveId = null) }
        }

        fun createCollective(
            name: String,
            emoji: String?,
        ) {
            if (_uiState.value.isCreating) return
            _uiState.update { it.copy(isCreating = true) }
            viewModelScope.launch {
                val result = repository.createCollective(name, emoji)
                val createdId = if (result is ApiResult.Success) result.data.id else null
                postMessage(if (result is ApiResult.Success) "Collective created" else result.userMessage())
                _uiState.update { it.copy(isCreating = false, createdCollectiveId = createdId) }
            }
        }

        fun setEmoji(
            collectiveId: Long,
            emoji: String,
        ) {
            viewModelScope.launch {
                val result = repository.setCollectiveEmoji(collectiveId, emoji)
                if (result !is ApiResult.Success) {
                    postMessage(result.userMessage())
                }
            }
        }

        fun trash(collectiveId: Long) {
            viewModelScope.launch {
                val result = repository.trashCollective(collectiveId)
                postMessage(if (result is ApiResult.Success) "Moved to trash" else result.userMessage())
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
