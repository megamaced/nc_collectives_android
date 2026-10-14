package com.megamaced.nccollectives.ui.screen.collective

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.prefs.CollectiveSort
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.domain.model.Collated
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.NotifyLevel
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.model.collated
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.sync.FullSync
import com.megamaced.nccollectives.sync.SyncOutcome
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
import com.megamaced.nccollectives.util.shouldAutoRefresh
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectiveListViewModel
    @Inject
    constructor(
        private val repository: CollectiveRepository,
        private val pageRepository: PageRepository,
        private val userPreferences: UserPreferences,
        private val fullSync: FullSync,
    ) : ViewModel() {
        private val sortFlow: Flow<CollectiveSort> =
            userPreferences.flow.map { it.collectiveSort }.distinctUntilChanged()

        val collectiveSort: StateFlow<CollectiveSort> = sortFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = CollectiveSort.NameAscending,
        )

        /**
         * The list in the user's chosen order. The page-timestamp flow is
         * only subscribed under the two time orders: it re-runs on every
         * write to `pages`, and an order by name has no use for it.
         */
        val collectives: StateFlow<List<Collective>> =
            sortFlow
                .flatMapLatest { sort ->
                    when (sort) {
                        CollectiveSort.NameAscending, CollectiveSort.NameDescending -> {
                            repository.observeCollectives().map { orderCollectives(it, emptyMap(), sort) }
                        }

                        CollectiveSort.NewestFirst, CollectiveSort.OldestFirst -> {
                            combine(
                                repository.observeCollectives(),
                                pageRepository.observeLastEditedByCollective(),
                            ) { collectives, lastEditedAt -> orderCollectives(collectives, lastEditedAt, sort) }
                        }
                    }
                }.stateIn(
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
                _uiState.update { it.copy(isRefreshing = false, errorMessage = result.userMessage()) }
            }
        }

        /**
         * The explicit pull. Unlike [refresh], which only re-lists
         * collectives, this pulls page metadata for every collective too —
         * the same run as Settings → Sync now — because the "Last edited"
         * order goes by every collective's pages, and someone who pulls to
         * refresh expects it to move. The automatic refreshes stay light: one request,
         * not two per collective on every return to this screen.
         */
        fun pullToRefresh() {
            if (_uiState.value.isRefreshing) return
            lastRefreshAt = System.currentTimeMillis()
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            viewModelScope.launch {
                val failure = when (val outcome = fullSync.run()) {
                    SyncOutcome.Success -> null
                    SyncOutcome.Unauthorised -> "Session expired — please log in again."
                    is SyncOutcome.Retryable -> outcome.message
                    is SyncOutcome.Failed -> outcome.message
                }
                _uiState.update { it.copy(isRefreshing = false, errorMessage = failure) }
                // The error arm of the list only shows while it's empty; a
                // pull the user asked for reports its failure either way.
                if (collectives.value.isNotEmpty()) postMessage(failure)
            }
        }

        fun setCollectiveSort(sort: CollectiveSort) {
            viewModelScope.launch { userPreferences.setCollectiveSort(sort) }
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
                postMessage(repository.setCollectiveEmoji(collectiveId, emoji).userMessage())
            }
        }

        fun setNotifyLevel(
            collectiveId: Long,
            level: NotifyLevel,
        ) {
            viewModelScope.launch {
                postMessage(repository.setNotifyLevel(collectiveId, level).userMessage())
            }
        }

        fun trash(collectiveId: Long) {
            viewModelScope.launch {
                val result = repository.trashCollective(collectiveId)
                postMessage(if (result is ApiResult.Success) "Moved to trash" else result.userMessage())
            }
        }
    }

/**
 * [collectives] in [sort] order. Names sort as page titles do (see
 * [Collated]), and are the tie-break for the time orders, which go by each
 * collective's most recently changed page in [lastEditedAt]. A collective
 * absent from it has no cached pages, so no time to sort on: it goes last
 * under both, by name.
 */
internal fun orderCollectives(
    collectives: List<Collective>,
    lastEditedAt: Map<Long, Long>,
    sort: CollectiveSort,
): List<Collective> {
    if (collectives.size < 2) return collectives
    val byName = compareBy<Collated<Collective>> { it.key }
    val unknownLast = compareBy<Collated<Collective>> { it.item.id !in lastEditedAt }
    val comparator = when (sort) {
        CollectiveSort.NameAscending -> byName
        CollectiveSort.NameDescending -> byName.reversed()
        CollectiveSort.NewestFirst -> unknownLast.thenByDescending { lastEditedAt[it.item.id] }.then(byName)
        CollectiveSort.OldestFirst -> unknownLast.thenBy { lastEditedAt[it.item.id] }.then(byName)
    }
    return collated(collectives) { it.name }.sortedWith(comparator).map { it.item }
}
