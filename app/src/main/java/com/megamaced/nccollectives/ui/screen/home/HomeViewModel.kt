package com.megamaced.nccollectives.ui.screen.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.auth.CurrentUser
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.model.sortedByTitle
import com.megamaced.nccollectives.domain.repository.AlertRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.sync.FullSync
import com.megamaced.nccollectives.sync.SyncOutcome
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The pages of [collectiveId] a new page can go under, in title order. */
data class NewPageParents(
    val collectiveId: Long,
    val choices: List<PageListItem>,
)

/**
 * A page the New page dialog created, and the Home visit that asked for it:
 * only that visit may open it on its own.
 */
data class CreatedPage(
    val pageId: Long,
    val title: String,
    val visit: Long,
)

/**
 * The Home tab: what needs the user, what they were reading, and what
 * changed, across every collective.
 *
 * Reads the Room cache only, so it works offline and costs nothing to open.
 * A pull runs the same full sync as Settings → Sync now, because the pages
 * shown here come from every collective.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
        private val currentUser: CurrentUser,
        private val fullSync: FullSync,
        alertRepository: AlertRepository,
    ) : ViewModel() {
        private val liveCollectives = collectiveRepository.observeCollectives()

        val collectives: StateFlow<List<Collective>> =
            liveCollectives.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

        private val savedRecentFilter = savedStateHandle.getStateFlow(KEY_RECENT_FILTER, ALL_COLLECTIVES)

        // Straight from the sources, not from [recentFilter]: that one starts
        // at null, and the feed built over it would show every collective's
        // pages for a frame before a saved filter applied.
        private val recentFilterSource: Flow<Long?> =
            combine(savedRecentFilter, liveCollectives, ::effectiveCollectiveFilter).distinctUntilChanged()

        /** Which collective "Recently updated" is narrowed to; null for all. */
        val recentFilter: StateFlow<Long?> =
            recentFilterSource.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

        /** Null until the cache has answered, so Home never flashes its empty state. */
        val feed: StateFlow<HomeFeed?> =
            combine(
                pageRepository.observeAttention(),
                pageRepository.observeRecentlyViewed(HOME_PREVIEW_CARDS + 1),
                currentUser.id.flatMapLatest { userId -> identitySections(userId) },
                recentFilterSource.flatMapLatest { pageRepository.observeRecentlyUpdated(it, HOME_PREVIEW_CARDS + 1) },
                pageRepository.observeTagCounts(),
            ) { attention, viewed, (updated, edits), recent, tags ->
                HomeFeed(
                    attention = attention,
                    jumpBackIn = viewed,
                    updatedSinceRead = updated,
                    recentlyUpdated = recent,
                    yourEdits = edits,
                    tags = tags,
                )
            }.combine(alertRepository.observeUnseenCount()) { feed, unseen -> feed.copy(unseenAlerts = unseen) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = null,
                )

        /** The collective a new page is going into, while the New page dialog is up. */
        private val _newPageCollectiveId = MutableStateFlow<Long?>(null)
        val newPageCollectiveId: StateFlow<Long?> = _newPageCollectiveId.asStateFlow()

        /**
         * The parents to offer, tagged with the collective they are from.
         * While another collective's are loading, these are still the
         * previous one's, and the tag is what stops the dialog offering them
         * for the new choice.
         */
        val newPageParents: StateFlow<NewPageParents?> =
            _newPageCollectiveId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(null)
                    } else {
                        pageRepository.observePageList(id).map { NewPageParents(id, sortedByTitle(it)) }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = null,
                )

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

        /** One-shot snackbar messages; see `CollectiveListViewModel.messages` (B-79). */
        private val _messages = Channel<String>(Channel.BUFFERED)
        val messages: Flow<String> = _messages.receiveAsFlow()

        private val _createdPages = Channel<CreatedPage>(Channel.BUFFERED)
        val createdPages: Flow<CreatedPage> = _createdPages.receiveAsFlow()

        /** A create is in flight; the dialog's Create waits for it. */
        private val _isCreatingPage = MutableStateFlow(false)
        val isCreatingPage: StateFlow<Boolean> = _isCreatingPage.asStateFlow()

        private var visits = 0L

        init {
            viewModelScope.launch { currentUser.refreshIfUnknown() }
            viewModelScope.launch {
                combine(savedRecentFilter, liveCollectives, ::isStaleCollectiveFilter)
                    .collect { stale -> if (stale) savedStateHandle[KEY_RECENT_FILTER] = ALL_COLLECTIVES }
            }
        }

        private fun identitySections(userId: String?): Flow<Pair<List<PageListItem>, List<PageListItem>>> =
            if (userId == null) {
                flowOf(emptyList<PageListItem>() to emptyList())
            } else {
                combine(
                    pageRepository.observeUpdatedSinceViewed(userId, HOME_PREVIEW_CARDS + 1),
                    pageRepository.observeLastEditedBy(userId, HOME_PREVIEW_CARDS + 1),
                ) { updated, edits -> updated to edits }
            }

        /** A new id for each time Home comes on screen; see [CreatedPage]. */
        fun beginVisit(): Long = ++visits

        fun setRecentFilter(collectiveId: Long?) {
            savedStateHandle[KEY_RECENT_FILTER] = collectiveId ?: ALL_COLLECTIVES
        }

        /**
         * Open the New page dialog's state: the one collective the user can
         * write to is chosen for them; with more, they choose.
         */
        fun startNewPage(editable: List<Collective>) {
            _newPageCollectiveId.value = editable.singleOrNull()?.id
        }

        fun selectNewPageCollective(collectiveId: Long) {
            _newPageCollectiveId.value = collectiveId
        }

        /**
         * Create the page the dialog describes, and say whether the request
         * was taken. Everything it needs arrives together, from what the
         * dialog was showing, so nothing is read back from state a later tap
         * could have moved. While one is in flight another is refused, and
         * the caller keeps its dialog open; [isCreatingPage] is set before
         * anything suspends.
         */
        fun createPage(
            collectiveId: Long,
            parentId: Long,
            title: String,
            visit: Long,
        ): Boolean {
            val cleaned = title.trim()
            if (cleaned.isEmpty() || _isCreatingPage.value) return false
            _isCreatingPage.value = true
            viewModelScope.launch {
                try {
                    when (val result = pageRepository.createPage(collectiveId, parentId, cleaned, body = "")) {
                        is ApiResult.Success -> _createdPages.trySend(CreatedPage(result.data.page.id, cleaned, visit))
                        else -> _messages.trySend(result.userMessage() ?: "Couldn't create the page")
                    }
                } finally {
                    _isCreatingPage.value = false
                }
            }
            return true
        }

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
            const val KEY_RECENT_FILTER = "recentFilter"
        }
    }
