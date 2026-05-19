package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.prefs.EditorPreference
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.domain.model.Attachment
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageTag
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.DirectEditingRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Destination the Edit button should navigate to (Batch 29). */
enum class EditRoute { Plain, Web }

/**
 * Result of resolving the Edit button's destination. Carries an optional
 * [fallbackMessage] to surface as a snackbar when we couldn't honour the
 * user's setting exactly (e.g. `AlwaysCollaborative` on a server that
 * doesn't support it).
 */
data class EditRouteDecision(
    val route: EditRoute,
    val fallbackMessage: String?,
)

data class PageViewUiState(
    val isLoadingBody: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    /** Available tags for the page's collective; populated when the tags sheet is opened. */
    val availableTags: List<PageTag> = emptyList(),
    val isLoadingTags: Boolean = false,
    /** Pages in the same collective, used as targets for the move sheet. */
    val movableTargets: List<Page> = emptyList(),
    /**
     * Set to the new page id after a successful duplicate (Batch 23). The
     * UI shows a "Copied — Open?" snackbar and clears this via
     * [acknowledgeCopied] once it's been surfaced.
     */
    val copiedPageId: Long? = null,
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
        private val directEditingRepository: DirectEditingRepository,
        private val userPreferences: UserPreferences,
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

        /**
         * Count of attachments on this page that have successfully uploaded
         * (status = `REMOTE`). Bumps whenever a queued upload completes —
         * the page screen keys the markdown view on this so Markwon
         * re-fetches images that previously 404'd while their upload was
         * still in flight (B-56).
         */
        val remoteAttachmentCount: StateFlow<Int> = attachmentRepository
            .observeForPage(pageId)
            .map { list -> list.count { it.status == Attachment.Status.REMOTE } }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

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

        /**
         * Resolve the Edit button's destination based on the user's
         * [EditorPreference] setting and runtime capability discovery
         * (Batch 29). Suspending because the capability lookup may hit
         * the network on the first call per session (memoised after).
         *
         * Returns the route to navigate to, plus a user-facing toast if
         * we couldn't honour the preference exactly (e.g.
         * `AlwaysCollaborative` on a server that doesn't expose
         * `directEditing`). Logs the routing decision at debug level
         * via Timber — no analytics endpoint, ever.
         */
        suspend fun resolveEditRoute(): EditRouteDecision {
            val preference = userPreferences.flow.first().editorPreference
            return when (preference) {
                EditorPreference.AlwaysPlain -> {
                    Timber.tag(TAG).d("Edit route: plain (preference=AlwaysPlain)")
                    EditRouteDecision(EditRoute.Plain, fallbackMessage = null)
                }
                EditorPreference.AlwaysCollaborative -> {
                    val available = directEditingRepository.isAvailable()
                    if (available) {
                        Timber.tag(TAG).d("Edit route: web (preference=AlwaysCollaborative, server=available)")
                        EditRouteDecision(EditRoute.Web, fallbackMessage = null)
                    } else {
                        Timber
                            .tag(TAG)
                            .d("Edit route: plain (preference=AlwaysCollaborative, server=unavailable; surfacing toast)")
                        EditRouteDecision(
                            route = EditRoute.Plain,
                            fallbackMessage = "Server doesn't support collaborative editing — opening plain editor.",
                        )
                    }
                }
                EditorPreference.Auto -> {
                    val available = directEditingRepository.isAvailable()
                    val route = if (available) EditRoute.Web else EditRoute.Plain
                    Timber.tag(TAG).d("Edit route: $route (preference=Auto, server=${if (available) "available" else "unavailable"})")
                    EditRouteDecision(route, fallbackMessage = null)
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

        /**
         * Create a tag on the current page's collective and immediately
         * attach it to the page (OCS-5, Batch 18k). The colour is fixed
         * to the brand slate-blue — Collectives uses the colour only as
         * a visual marker and the tag picker doesn't surface it yet.
         */
        fun createTag(name: String) {
            val current = page.value ?: return
            val cleaned = name.trim()
            if (cleaned.isEmpty()) return
            viewModelScope.launch {
                val createResult = pageRepository.createTag(
                    collectiveId = current.collectiveId,
                    name = cleaned,
                    color = NEW_TAG_COLOUR,
                )
                when (createResult) {
                    is ApiResult.Success -> {
                        val created = createResult.data
                        _uiState.update { state ->
                            val nextAvailable = (state.availableTags + created)
                                .distinctBy { it.id }
                                .sortedBy { it.name.lowercase() }
                            state.copy(availableTags = nextAvailable)
                        }
                        // Attach the new tag to the current page so the user
                        // doesn't have to click again.
                        togglePageTag(created, add = true)
                    }
                    else -> _uiState.update {
                        it.copy(statusMessage = createResult.userMessage() ?: "Couldn't create tag")
                    }
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
                // B-39: drop the moved page and its descendants — a cycle
                // (move A under one of A's own children) bricks the page
                // tree on the server. Walk parents toward the root, stop
                // at a malformed chain (parentId → missing row).
                val byId = list.associateBy { it.id }
                val descendantIds = buildSet {
                    for (candidate in list) {
                        var cursor: Page? = byId[candidate.parentId]
                        while (cursor != null) {
                            if (cursor.id == pageId) {
                                add(candidate.id)
                                break
                            }
                            cursor = byId[cursor.parentId]
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        movableTargets = list.filter { p -> p.id != pageId && p.id !in descendantIds },
                    )
                }
            }
        }

        fun movePage(newParentPageId: Long) {
            viewModelScope.launch {
                val result = pageRepository.movePage(pageId, newParentPageId)
                _uiState.update { it.copy(statusMessage = renameOrMoveMessage(result, "moved")) }
            }
        }

        fun duplicatePage() {
            val current = page.value ?: return
            viewModelScope.launch {
                val result = pageRepository.copyPage(current.collectiveId, current.id)
                _uiState.update {
                    when (result) {
                        is ApiResult.Success ->
                            it.copy(copiedPageId = result.data.id)
                        else ->
                            it.copy(statusMessage = result.userMessage())
                    }
                }
            }
        }

        fun acknowledgeCopied() {
            _uiState.update { it.copy(copiedPageId = null) }
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
            const val TAG = "PageViewModel"

            /**
             * Default colour for tags created in-app — 6-hex without `#`
             * per `ENDPOINTS.md` gotcha #2. Roughly matches the brand
             * slate-blue (`0xFF38618C` in [com.megamaced.nccollectives.ui.theme.Color]).
             */
            const val NEW_TAG_COLOUR = "38618c"
        }
    }
