package com.megamaced.nccollectives.ui.screen.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.auth.CurrentUser
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What a "See all" screen lists: attention rows, or pages for every other section. */
internal sealed interface HomeSectionContent {
    data class Attention(
        val rows: List<AttentionRow>,
    ) : HomeSectionContent

    /** [hasMore]: the list stopped at the current limit; [HomeSectionViewModel.loadMore] goes on. */
    data class Pages(
        val pages: List<PageListItem>,
        val hasMore: Boolean,
    ) : HomeSectionContent
}

/** One Home section in full, behind its "See all". */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeSectionViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
        private val currentUser: CurrentUser,
    ) : ViewModel() {
        val section: HomeSection = checkNotNull(
            HomeSection.fromKey(savedStateHandle[Destination.HomeSeeAll.ARG_SECTION]),
        )

        val collectives: StateFlow<List<Collective>> =
            collectiveRepository.observeCollectives().stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

        private val savedRecentFilter = savedStateHandle.getStateFlow(Destination.HomeSeeAll.ARG_COLLECTIVE, ALL_COLLECTIVES)

        // See `HomeViewModel.recentFilterSource` for why the content reads
        // this rather than [recentFilter].
        private val recentFilterSource: Flow<Long?> =
            combine(savedRecentFilter, collectiveRepository.observeCollectives(), ::effectiveCollectiveFilter)
                .distinctUntilChanged()

        /** Only "Recently updated" is narrowed; it starts on the collective Home had chosen. */
        val recentFilter: StateFlow<Long?> =
            recentFilterSource.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

        private val limit = MutableStateFlow(HOME_SECTION_LIMIT)

        init {
            if (section == HomeSection.RecentlyUpdated) {
                viewModelScope.launch {
                    combine(savedRecentFilter, collectiveRepository.observeCollectives(), ::isStaleCollectiveFilter)
                        .collect { stale -> if (stale) savedStateHandle[Destination.HomeSeeAll.ARG_COLLECTIVE] = ALL_COLLECTIVES }
                }
            }
        }

        /** Null until the cache has answered. */
        internal val content: StateFlow<HomeSectionContent?> =
            contentFlow().stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

        private fun contentFlow(): Flow<HomeSectionContent> =
            when (section) {
                HomeSection.Attention -> pageRepository.observeAttention().map { HomeSectionContent.Attention(it.rows()) }

                HomeSection.JumpBackIn -> pages { pageRepository.observeRecentlyViewed(it) }

                HomeSection.RecentlyUpdated -> recentFilterSource.flatMapLatest { filter ->
                    pages { pageRepository.observeRecentlyUpdated(filter, it) }
                }

                HomeSection.UpdatedSinceRead -> forUser { userId -> pages { pageRepository.observeUpdatedSinceViewed(userId, it) } }

                HomeSection.YourEdits -> forUser { userId -> pages { pageRepository.observeLastEditedBy(userId, it) } }
            }

        /** Nothing until the server has said who the user is, as on Home. */
        private fun forUser(query: (String) -> Flow<HomeSectionContent>): Flow<HomeSectionContent> =
            currentUser.id.flatMapLatest { userId ->
                if (userId == null) flowOf(HomeSectionContent.Pages(emptyList(), hasMore = false)) else query(userId)
            }

        /**
         * [query] up to the current limit. One row more is asked for than
         * shown, and its arrival is what says there is more to load.
         */
        private fun pages(query: (limit: Int) -> Flow<List<PageListItem>>): Flow<HomeSectionContent> =
            limit.flatMapLatest { shown ->
                query(shown + 1).map { HomeSectionContent.Pages(it.take(shown), hasMore = it.size > shown) }
            }

        fun loadMore() {
            limit.value += HOME_SECTION_LIMIT
        }

        fun setRecentFilter(collectiveId: Long?) {
            savedStateHandle[Destination.HomeSeeAll.ARG_COLLECTIVE] = collectiveId ?: ALL_COLLECTIVES
        }
    }
