package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageTag
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PageViewUiState(
    val isLoadingBody: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    /** Available tags for the page's collective; populated when the tags sheet is opened. */
    val availableTags: List<PageTag> = emptyList(),
    val isLoadingTags: Boolean = false,
    /** Pages in the same collective, used as targets for the move sheet. */
    val movableTargets: List<Page> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PageViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val pageRepository: PageRepository,
        private val collectiveRepository: CollectiveRepository,
        private val attachmentRepository: AttachmentRepository,
    ) : ViewModel() {
        private val pageId: Long = checkNotNull(
            savedStateHandle.get<Long>(Destination.PageView.ARG_PAGE_ID),
        )

        val page: StateFlow<Page?> = pageRepository.observePage(pageId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

        private val _imageBaseUrl = MutableStateFlow<String?>(null)
        val imageBaseUrl: StateFlow<String?> = _imageBaseUrl.asStateFlow()

        val isFavorite: StateFlow<Boolean> = combine(
            page,
            collectiveRepository.observeCollectives(),
        ) { p, collectives ->
            p?.let { current ->
                collectives.firstOrNull { it.id == current.collectiveId }?.favoritePageIds?.contains(current.id) ?: false
            } ?: false
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        val backlinks: StateFlow<List<Page>> = page
            .flatMapLatest { current ->
                if (current == null) {
                    flowOf(emptyList())
                } else {
                    pageRepository.observeBacklinksFor(current.collectiveId, current.id)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        private val _uiState = MutableStateFlow(PageViewUiState())
        val uiState: StateFlow<PageViewUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val cached = pageRepository.getPage(pageId)
                if (cached != null && cached.bodyMd == null) refreshBody()
                _imageBaseUrl.value = attachmentRepository.attachmentsBaseUrl(pageId)
            }
        }

        fun refreshBody() {
            if (_uiState.value.isLoadingBody) return
            _uiState.update { it.copy(isLoadingBody = true, errorMessage = null) }
            viewModelScope.launch {
                val result = pageRepository.fetchBody(pageId)
                _uiState.update { state ->
                    state.copy(
                        isLoadingBody = false,
                        errorMessage = if (result is ApiResult.Success) null else result.userMessage(),
                    )
                }
            }
        }

        fun toggleFavorite() {
            val current = page.value ?: return
            val want = !isFavorite.value
            viewModelScope.launch {
                val result = collectiveRepository.toggleFavorite(
                    collectiveId = current.collectiveId,
                    pageId = current.id,
                    favorite = want,
                )
                if (result !is ApiResult.Success) {
                    _uiState.update { it.copy(statusMessage = result.userMessage()) }
                }
            }
        }

        fun setEmoji(emoji: String) {
            viewModelScope.launch {
                val result = pageRepository.setEmoji(pageId, emoji)
                if (result !is ApiResult.Success) {
                    _uiState.update { it.copy(statusMessage = result.userMessage()) }
                }
            }
        }

        fun loadAvailableTags() {
            val current = page.value ?: return
            if (_uiState.value.isLoadingTags) return
            _uiState.update { it.copy(isLoadingTags = true) }
            viewModelScope.launch {
                val result = pageRepository.listTagsForCollective(current.collectiveId)
                _uiState.update { state ->
                    state.copy(
                        isLoadingTags = false,
                        availableTags = if (result is ApiResult.Success) result.data else state.availableTags,
                        statusMessage = if (result is ApiResult.Success) state.statusMessage else result.userMessage(),
                    )
                }
            }
        }

        fun togglePageTag(
            tag: PageTag,
            add: Boolean,
        ) {
            viewModelScope.launch {
                val result = pageRepository.togglePageTag(pageId, tag.id, tag.name, add)
                if (result !is ApiResult.Success) {
                    _uiState.update { it.copy(statusMessage = result.userMessage()) }
                }
            }
        }

        fun renamePage(newTitle: String) {
            viewModelScope.launch {
                val result = pageRepository.renamePage(pageId, newTitle)
                _uiState.update { it.copy(statusMessage = renameOrMoveMessage(result, "renamed")) }
            }
        }

        fun loadMoveTargets() {
            val current = page.value ?: return
            viewModelScope.launch {
                // `.first()` takes a snapshot and unsubscribes — previously
                // `.collect { … return@collect }` left a Room observer running
                // for the screen's lifetime (B-7).
                val list = pageRepository.observePages(current.collectiveId).first()
                _uiState.update {
                    it.copy(movableTargets = list.filter { p -> p.id != pageId })
                }
            }
        }

        fun movePage(newParentPageId: Long) {
            viewModelScope.launch {
                val result = pageRepository.movePage(pageId, newParentPageId)
                _uiState.update { it.copy(statusMessage = renameOrMoveMessage(result, "moved")) }
            }
        }

        fun trashPage(onTrashed: () -> Unit) {
            viewModelScope.launch {
                val result = pageRepository.trashPage(pageId)
                if (result is ApiResult.Success) {
                    onTrashed()
                } else {
                    _uiState.update { it.copy(statusMessage = result.userMessage()) }
                }
            }
        }

        fun replaceWithDraft() {
            val draft = page.value?.draftBodyMd ?: return
            viewModelScope.launch {
                val outcome = pageRepository.replaceWithDraft(pageId, draft)
                _uiState.update { it.copy(statusMessage = saveOutcomeMessage(outcome)) }
            }
        }

        fun discardDraft() {
            viewModelScope.launch { pageRepository.discardDraft(pageId) }
        }

        fun dismissStatus() {
            _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
        }

        /**
         * Resolve a wikilink (or relative markdown reference) to a page id in
         * the same collective. Returns null if the target isn't cached — the
         * caller should fall back to a status message.
         */
        fun resolveWikilink(
            target: String,
            onResolved: (Long) -> Unit,
        ) {
            val current = page.value ?: return
            viewModelScope.launch {
                val resolved = pageRepository.resolvePageByTitle(current.collectiveId, target)
                if (resolved != null) {
                    onResolved(resolved)
                } else {
                    _uiState.update {
                        it.copy(statusMessage = "Linked page \"$target\" not found")
                    }
                }
            }
        }

        private fun renameOrMoveMessage(
            result: ApiResult<Unit>,
            verb: String,
        ): String? =
            when (result) {
                is ApiResult.Success -> "Page $verb"
                else -> result.userMessage()
            }

        private fun saveOutcomeMessage(outcome: SaveOutcome): String? =
            when (outcome) {
                SaveOutcome.Saved -> "Page replaced"
                SaveOutcome.Queued -> "Saved offline — will sync when online"
                SaveOutcome.Conflict -> "Still conflicting — try again later"
                is SaveOutcome.Error -> outcome.message
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
