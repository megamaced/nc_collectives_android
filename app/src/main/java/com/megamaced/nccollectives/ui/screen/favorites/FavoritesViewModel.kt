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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
        val favorites: StateFlow<List<FavoriteEntry>> = collectiveRepository
            .observeCollectives()
            .flatMapLatest { collectives ->
                if (collectives.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val perCollective = collectives.map { c ->
                        pageRepository.observePages(c.id).let { flow ->
                            combine(flow, flowOf(c)) { pages, collective ->
                                pages
                                    .filter { it.id in collective.favoritePageIds }
                                    .map { FavoriteEntry(it, collective.name) }
                            }
                        }
                    }
                    combine(perCollective) { arrays ->
                        arrays.toList().flatten().sortedBy { it.page.title.lowercase() }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        init {
            // Make sure both caches have at least had one chance to refresh so
            // favorites appear even before the user has opened a collective.
            viewModelScope.launch {
                collectiveRepository.refresh()
                val current = collectiveRepository.observeCollectives()
                current.collect { list ->
                    list.forEach { c -> pageRepository.refresh(c.id) }
                    return@collect
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
