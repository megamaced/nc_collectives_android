package com.megamaced.nccollectives.ui.screen.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoriteEntry(
    val page: Page,
    val collectiveName: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel
    @Inject
    constructor(
        private val collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
    ) : ViewModel() {
        // R-24: gate the per-collective observe-pages fan-out on the
        // *set of collective ids*, not on the whole collective list.
        // The previous flatMapLatest rebuilt the N-way combine on every
        // collective-row update — including each favorite toggle, which
        // writes back to `userFavoritePagesCsv`. Now membership changes
        // (rare) tear down + rebuild; favorite toggles cause the inner
        // observeCollectives() flow to re-emit but the pages flows stay
        // subscribed to Room and re-deliver from the cache.
        val favorites: StateFlow<List<FavoriteEntry>> = collectiveRepository
            .observeCollectives()
            .map { list -> list.map { it.id } }
            .distinctUntilChanged()
            .flatMapLatest { collectiveIds ->
                if (collectiveIds.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val pagesFlows = collectiveIds.map { id -> pageRepository.observePages(id) }
                    combine(
                        combine(pagesFlows) { it.toList() },
                        collectiveRepository.observeCollectives(),
                    ) { pagesPerCollective, collectives ->
                        val byId = collectives.associateBy { it.id }
                        collectiveIds
                            .flatMapIndexed { idx, collectiveId ->
                                val collective = byId[collectiveId] ?: return@flatMapIndexed emptyList()
                                pagesPerCollective[idx]
                                    .filter { it.id in collective.favoritePageIds }
                                    .map { FavoriteEntry(it, collective.name) }
                            }.sortedBy { it.page.title.lowercase() }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

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

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
