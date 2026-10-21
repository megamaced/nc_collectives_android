package com.megamaced.nccollectives.ui.screen.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
import com.megamaced.nccollectives.ui.screen.onFailureMessage
import com.megamaced.nccollectives.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One collective's favorites, under its name, A–Z by title.
 *
 * R-54: the pages are [PageListItem]s. A favorite row renders an emoji and a
 * title. This screen fans its observers out across *every* collective at
 * once, which makes it the last place that should be reading cached
 * markdown no row draws.
 */
data class FavoriteGroup(
    val collectiveId: Long,
    /** The collective's name, with its emoji when it has one. */
    val collectiveLabel: String,
    val pages: List<PageListItem>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel
    @Inject
    constructor(
        private val collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
    ) : ViewModel() {
        /**
         * Every collective with favorites, A–Z by name; collectives without
         * any are left out.
         *
         * R-24: the per-collective observe-pages fan-out is gated on the *set
         * of collective ids*, not on the whole collective list, so it isn't
         * rebuilt on every collective-row update — including each favorite
         * toggle, which writes back to `userFavoritePagesCsv`. Membership
         * changes (rare) tear down and rebuild; favorite toggles cause the
         * inner observeCollectives() flow to re-emit while the pages flows
         * stay subscribed to Room and re-deliver from the cache.
         */
        val groups: StateFlow<List<FavoriteGroup>> = collectiveRepository
            .observeCollectives()
            .map { list -> list.map { it.id } }
            .distinctUntilChanged()
            .flatMapLatest { collectiveIds ->
                if (collectiveIds.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val pagesFlows = collectiveIds.map { id -> pageRepository.observePageList(id) }
                    combine(
                        combine(pagesFlows) { it.toList() },
                        collectiveRepository.observeCollectives(),
                    ) { pagesPerCollective, collectives ->
                        val byId = collectives.associateBy { it.id }
                        collectiveIds
                            .mapIndexedNotNull { idx, collectiveId ->
                                val collective = byId[collectiveId] ?: return@mapIndexedNotNull null
                                val pages = pagesPerCollective[idx]
                                    .filter { it.id in collective.favoritePageIds }
                                    .sortedBy { it.title.lowercase() }
                                if (pages.isEmpty()) return@mapIndexedNotNull null
                                collective.name.lowercase() to FavoriteGroup(collective.id, collective.label, pages)
                            }.sortedBy { (sortKey, _) -> sortKey }
                            .map { (_, group) -> group }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        private val _removed = Channel<PageListItem>(Channel.BUFFERED)

        /** Each favorite the user just removed from this screen, so it can be offered back. */
        val removed: Flow<PageListItem> = _removed.receiveAsFlow()

        private val _messages = Channel<UiText>(Channel.BUFFERED)

        /** One-shot messages: a change the server refused. */
        val messages: Flow<UiText> = _messages.receiveAsFlow()

        init {
            // Make sure both caches have at least had one chance to refresh so
            // favorites appear even before the user has opened a collective.
            // Previously this used `.collect { … return@collect }`, which
            // doesn't unsubscribe — every favourite toggle re-triggered a
            // refresh fan-out across every collective (B-8 / R-6).
            viewModelScope.launch {
                collectiveRepository.refresh()
                val list = collectiveRepository.observeCollectives().first()
                list.forEach { c -> pageRepository.refresh(c.id) }
            }
        }

        /** Unstar [page]. Its row leaves at once, and comes back if the server refuses. */
        fun unfavorite(page: PageListItem) {
            setFavorite(page, favorite = false) { _removed.trySend(page) }
        }

        /** Undo: star [page] again. */
        fun restore(page: PageListItem) {
            setFavorite(page, favorite = true) {}
        }

        private fun setFavorite(
            page: PageListItem,
            favorite: Boolean,
            onDone: () -> Unit,
        ) {
            viewModelScope.launch {
                val result = collectiveRepository.toggleFavorite(
                    collectiveId = page.collectiveId,
                    pageId = page.id,
                    favorite = favorite,
                )
                if (result is ApiResult.Success) onDone() else result.onFailureMessage { _messages.trySend(it) }
            }
        }
    }
