package com.megamaced.nccollectives.ui.screen.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.domain.repository.observeFavoritePageIds
import com.megamaced.nccollectives.ui.navigation.Destination
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
import com.megamaced.nccollectives.ui.screen.onFailureMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * R-54: a [PageListItem]. Browse-by-tag draws the same row the tree does,
 * and the tag match itself is decided from `tagsCsv` in the repository —
 * neither needs a body.
 *
 * [collectiveLabel] names the page's collective when the browse spans every
 * collective, and is null inside one.
 */
data class TagBrowseRow(
    val page: PageListItem,
    val isFavorite: Boolean,
    val collectiveLabel: String? = null,
)

data class TagBrowseUiState(
    val collectiveName: String = "",
    val tagName: String = "",
    val statusMessage: String? = null,
)

@HiltViewModel
class TagBrowseViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
    ) : ViewModel() {
        val collectiveId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.TagBrowse.ARG_COLLECTIVE_ID),
        )

        private val acrossCollectives = collectiveId == Destination.TagBrowse.ALL_COLLECTIVES

        // NavType.StringType already URL-decodes the path argument.
        val tagName: String = checkNotNull(
            savedStateHandle.get<String>(Destination.TagBrowse.ARG_TAG_NAME),
        )

        private val _uiState = MutableStateFlow(
            TagBrowseUiState(
                tagName = tagName,
                collectiveName = if (acrossCollectives) "All collectives" else "",
            ),
        )
        val uiState: StateFlow<TagBrowseUiState> = _uiState.asStateFlow()

        val rows: StateFlow<List<TagBrowseRow>> =
            if (acrossCollectives) {
                combine(
                    pageRepository.observePagesWithTag(tagName),
                    collectiveRepository
                        .observeCollectives()
                        .map { collectives ->
                            // Page ids are server file ids, unique across
                            // collectives, so one set covers them all.
                            collectives.flatMapTo(HashSet()) { it.favoritePageIds } to
                                collectives.associate { it.id to it.label }
                        }.distinctUntilChanged(),
                ) { pages, (favoriteIds, labels) ->
                    pages.map {
                        TagBrowseRow(page = it, isFavorite = it.id in favoriteIds, collectiveLabel = labels[it.collectiveId])
                    }
                }
            } else {
                combine(
                    pageRepository.observePagesWithTagInCollective(collectiveId, tagName),
                    collectiveRepository.observeFavoritePageIds(collectiveId),
                ) { pages, favoriteIds ->
                    pages.map { TagBrowseRow(page = it, isFavorite = it.id in favoriteIds) }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

        init {
            if (!acrossCollectives) {
                viewModelScope.launch {
                    // R-35: snapshot via `.first()` — the collective name can't
                    // change at runtime (the server has no rename endpoint;
                    // Batch 22's `CreateCollectiveSheet` says so) so holding a
                    // long-running Room observer for a value that's stable for
                    // the screen's lifetime is wasted machinery.
                    val snapshot = collectiveRepository.observeCollectives().first()
                    snapshot.firstOrNull { it.id == collectiveId }?.let { c ->
                        _uiState.update { it.copy(collectiveName = c.name) }
                    }
                }
            }
        }

        /**
         * Star or unstar [row]'s page in the collective it belongs to — the
         * row's own, which is not [collectiveId] when browsing every
         * collective.
         */
        fun toggleFavorite(row: TagBrowseRow) {
            viewModelScope.launch {
                val result = collectiveRepository.toggleFavorite(
                    collectiveId = row.page.collectiveId,
                    pageId = row.page.id,
                    favorite = !row.isFavorite,
                )
                result.onFailureMessage { message ->
                    _uiState.update { it.copy(statusMessage = message) }
                }
            }
        }

        fun dismissStatus() {
            _uiState.update { it.copy(statusMessage = null) }
        }
    }
